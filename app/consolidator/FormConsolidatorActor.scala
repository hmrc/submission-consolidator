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

import java.time.Instant
import java.time.Instant.ofEpochMilli
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, Props }
import cats.effect.IO
import cats.implicits._
import com.codahale.metrics.MetricRegistry.name
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import common.MetricsClient
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.{ ConsolidatorService, SubmissionService }
import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.lock.{ LockKeeperAutoRenew, LockRepository }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }

class FormConsolidatorActor(
  consolidatorService: ConsolidatorService,
  fileUploaderService: SubmissionService,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  lockRepository: LockRepository,
  metricsClient: MetricsClient
) extends Actor with IOUtils {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case MessageWithFireTime(p: ConsolidatorJobParam, time: Date) =>
      logger.info(s"Received request for job $p")
      val senderRef = sender()
      val program: IO[Unit] = (for {
        consolidationResult <- consolidatorService.doConsolidation(p.projectId)
        envelopeId <- if (consolidationResult.isDefined)
                       fileUploaderService
                         .submit(consolidationResult.get.outputPath, p)
                         .map(Option(_))
                     else
                       IO.pure(None)
        _ <- addConsolidatorJobData(
              p.projectId,
              ofEpochMilli(time.getTime),
              consolidationResult,
              None,
              envelopeId
            )
      } yield ()).recoverWith {
        case e =>
          logger.error(s"Failed to consolidate/submit forms for project ${p.projectId}", e)
          addConsolidatorJobData(p.projectId, ofEpochMilli(time.getTime), None, Some(e.getMessage), None)
            .flatMap(_ => IO.raiseError(e))
      }

      val lock = new LockKeeperAutoRenew {
        override val repo: LockRepository = lockRepository
        override val id: String = p.projectId
        override val duration: org.joda.time.Duration = org.joda.time.Duration.standardMinutes(5)
      }

      lock
        .withLock(program.unsafeToFuture())
        .onComplete {
          case Success(Some(_)) =>
            senderRef ! OK
          case Success(None) =>
            senderRef ! LockUnavailable
          case Failure(e) => senderRef ! e
        }
  }

  private def addConsolidatorJobData(
    projectId: String,
    startTime: Instant,
    consolidationResult: Option[ConsolidationResult],
    error: Option[String],
    envelopeId: Option[String]
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
      envelopeId
    )
    liftIO(consolidatorJobDataRepository.add(consolidatorJobData))
  }
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
    metricsClient: MetricsClient
  ): Props =
    Props(
      new FormConsolidatorActor(
        consolidatorService,
        fileUploaderService,
        consolidatorJobDataRepository,
        lockRepository,
        metricsClient
      )
    )
}
