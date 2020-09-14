/*
 * Copyright 2020 HM Revenue & Customs
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

import java.nio.file.Files.createDirectories
import java.nio.file.{ Path, Paths }
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.Instant.ofEpochMilli
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, Props }
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.codahale.metrics.MetricRegistry.name
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import common.MetricsClient
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.{ ConsolidatorService, DeleteDirService, SubmissionService }
import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.lock.{ LockKeeperAutoRenew, LockRepository }

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }

class FormConsolidatorActor(
  consolidatorService: ConsolidatorService,
  fileUploaderService: SubmissionService,
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
    case MessageWithFireTime(jobParam: ConsolidatorJobParam, time: Date) =>
      if (!runningProjects.contains(jobParam.projectId)) {
        runningProjects += jobParam.projectId
        logger.info(s"Received request for job $jobParam")
        val senderRef = sender()
        val reportOutputDir = createReportDir(jobParam.projectId, time)
        val program: IO[Unit] = (for {
          consolidationResult <- consolidatorService.doConsolidation(jobParam.projectId, reportOutputDir)
          envelopeIds <- if (consolidationResult.count > 0)
                          fileUploaderService
                            .submit(consolidationResult.outputPath, jobParam)
                            .map(Option(_))
                        else
                          IO.pure(None)
          _ <- addConsolidatorJobData(
                jobParam.projectId,
                ofEpochMilli(time.getTime),
                Some(consolidationResult),
                None,
                envelopeIds
              )
          _ <- deleteReportTmpDir(consolidationResult.outputPath)
        } yield ()).recoverWith {
          case e =>
            logger.error(s"Failed to consolidate/submit forms for project ${jobParam.projectId}", e)
            for {
              _ <- deleteReportTmpDir(reportOutputDir)
              _ <- addConsolidatorJobData(
                    jobParam.projectId,
                    ofEpochMilli(time.getTime),
                    None,
                    Some(e.getMessage),
                    None)
                    .flatMap(_ => IO.raiseError(e))
            } yield ()
        }

        val lock = new LockKeeperAutoRenew {
          override val repo: LockRepository = lockRepository
          override val id: String = jobParam.projectId
          override val duration: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)
        }

        lock
          .withLock(program.unsafeToFuture())
          .onComplete { result =>
            runningProjects -= jobParam.projectId
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
    projectId: String,
    startTime: Instant,
    consolidationResult: Option[ConsolidationResult],
    error: Option[String],
    envelopeIds: Option[NonEmptyList[String]]
  ): IO[Unit] = {
    val now = Instant.now()
    metricsClient.recordDuration(
      name("consolidator", projectId, "run"),
      Duration(now.toEpochMilli - startTime.toEpochMilli, TimeUnit.MILLISECONDS)
    )
    error.foreach { e =>
      metricsClient.markMeter(name("consolidator", projectId, "failed"))
    }
    consolidationResult.foreach { cResult =>
      metricsClient.markMeter(name("consolidator", projectId, "success"))
      metricsClient.markMeter(name("consolidator", projectId, "formCount"), cResult.count)
    }
    val consolidatorJobData = ConsolidatorJobData(
      projectId,
      startTime,
      now,
      consolidationResult.flatMap(_.lastObjectId),
      error,
      envelopeIds.map(_.mkString_(","))
    )
    liftIO(consolidatorJobDataRepository.add(consolidatorJobData))
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
    fileUploaderService: SubmissionService,
    consolidatorJobDataRepository: ConsolidatorJobDataRepository,
    lockRepository: LockRepository,
    metricsClient: MetricsClient,
    deleteDirService: DeleteDirService
  ): Props =
    Props(
      new FormConsolidatorActor(
        consolidatorService,
        fileUploaderService,
        consolidatorJobDataRepository,
        lockRepository,
        metricsClient,
        deleteDirService
      )
    )
}
