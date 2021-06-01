/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package consolidator

import akka.actor.{ Actor, Props }
import cats.effect.IO
import cats.implicits._
import com.codahale.metrics.MetricRegistry.name
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import common.MetricsClient
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.{ FileUpload, S3 }
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services._
import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.lock.{ LockKeeperAutoRenew, LockRepository }

import java.nio.file.Files.createDirectories
import java.nio.file.{ Path, Paths }
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.Instant.ofEpochMilli
import java.util.Date
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }

class FormConsolidatorActor(
  consolidatorService: ConsolidatorService,
  fileUploaderSubmissionService: FileUploadSubmissionService,
  s3SubmissionService: S3SubmissionService,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  lockRepository: LockRepository,
  metricsClient: MetricsClient,
  deleteDirService: DeleteDirService
) extends Actor with IOUtils {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContext = context.dispatcher
  private val DATE_TIME_FORMAT = new SimpleDateFormat("yyyyMMddHHmmssSSS")

  private val runningProjects: mutable.Set[String] = mutable.Set[String]()

  override def receive: Receive = {
    case MessageWithFireTime(params: FormConsolidatorParams, time: Date) =>
      if (!runningProjects.contains(params.projectId)) {
        runningProjects += params.projectId
        logger.info(s"Received request for job $params")
        val senderRef = sender()
        val reportOutputDir = createReportDir(params.projectId, time)
        val program: IO[Unit] = (for {
          consolidationResult <- consolidatorService.doConsolidation(reportOutputDir, params)
          submissionResult <- consolidationResult
                               .map { c =>
                                 params.destination match {
                                   case s3: S3 =>
                                     s3SubmissionService
                                       .submit(c.reportFiles, s3)
                                       .map(Option(_))
                                   case fileUpload: FileUpload =>
                                     fileUploaderSubmissionService
                                       .submit(c.reportFiles, params.projectId, params.format, fileUpload)
                                       .map(Option(_))
                                 }
                               }
                               .getOrElse(IO.pure(None))
          _ <- addConsolidatorJobData(
                params,
                ofEpochMilli(time.getTime),
                consolidationResult,
                None,
                submissionResult
              )
          _ <- deleteReportTmpDir(reportOutputDir)
        } yield ()).recoverWith {
          case e =>
            logger.error(s"Failed to consolidate/submit forms for project ${params.projectId}", e)
            (for {
              _ <- deleteReportTmpDir(reportOutputDir)
              _ <- addConsolidatorJobData(params, ofEpochMilli(time.getTime), None, Some(e.getMessage), None)
            } yield ()).flatMap(_ => IO.raiseError(e))
        }

        val lock = new LockKeeperAutoRenew {
          override val repo: LockRepository = lockRepository
          override val id: String = params.projectId
          override val duration: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)
        }

        lock
          .withLock(program.unsafeToFuture())
          .onComplete { result =>
            runningProjects -= params.projectId
            result match {
              case Success(Some(_)) =>
                senderRef ! OK
              case Success(None) =>
                senderRef ! LockUnavailable
              case Failure(e) => senderRef ! e
            }
          }
      }
  }

  private def addConsolidatorJobData(
    params: FormConsolidatorParams,
    startTime: Instant,
    consolidationResult: Option[ConsolidationResult],
    error: Option[String],
    maybeSubmissionResult: Option[SubmissionResult]
  ): IO[Unit] = {
    consolidationResult.foreach { cResult =>
      logger.info(
        s"Submitted ${cResult.count} forms to destination ${params.destination} for project ${params.projectId}")
    }
    params match {
      case params: ScheduledFormConsolidatorParams =>
        val now = Instant.now()
        metricsClient.recordDuration(
          name("consolidator", params.projectId, "run"),
          Duration(now.toEpochMilli - startTime.toEpochMilli, TimeUnit.MILLISECONDS)
        )
        error.foreach { e =>
          metricsClient.markMeter(name("consolidator", params.projectId, "failed"))
        }
        consolidationResult.foreach { cResult =>
          metricsClient.markMeter(name("consolidator", params.projectId, "success"))
          metricsClient.markMeter(name("consolidator", params.projectId, "formCount"), cResult.count)
        }
        val consolidatorJobData = ConsolidatorJobData(
          params.projectId,
          startTime,
          now,
          consolidationResult.map(_.lastObjectId),
          error,
          maybeSubmissionResult.map {
            case FileUploadSubmissionResult(envelopeIds) => envelopeIds.toList.mkString(",")
            case S3SubmissionResult(_)                   => ""
          },
          maybeSubmissionResult
        )
        liftIO(consolidatorJobDataRepository.add(consolidatorJobData))
      case _ =>
        IO.pure(())
    }
  }

  private def deleteReportTmpDir(path: Path) =
    liftIO {
      deleteDirService.deleteDir(path)
    }

  private def createReportDir(projectId: String, time: Date): Path =
    createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/submission-consolidator/$projectId-${DATE_TIME_FORMAT
        .format(time)}")
    )
}

object FormConsolidatorActor {

  sealed trait Status
  case object LockUnavailable extends Status
  case object OK extends Status

  def props(
    consolidatorService: ConsolidatorService,
    fileUploaderSubmissionService: FileUploadSubmissionService,
    s3SubmissionService: S3SubmissionService,
    consolidatorJobDataRepository: ConsolidatorJobDataRepository,
    lockRepository: LockRepository,
    metricsClient: MetricsClient,
    deleteDirService: DeleteDirService
  ): Props =
    Props(
      new FormConsolidatorActor(
        consolidatorService,
        fileUploaderSubmissionService,
        s3SubmissionService,
        consolidatorJobDataRepository,
        lockRepository,
        metricsClient,
        deleteDirService
      )
    )
}
