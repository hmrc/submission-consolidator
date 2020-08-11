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

import akka.actor.{ Actor, Props }
import cats.effect.IO
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.{ ConsolidatorService, SubmissionService }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext
import cats.implicits._
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.lock.{ LockKeeperAutoRenew, LockRepository }
import org.joda.time.Duration

import scala.util.{ Failure, Success }

class FormConsolidatorActor(
  consolidatorService: ConsolidatorService,
  fileUploaderService: SubmissionService,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  lockRepository: LockRepository
) extends Actor with IOUtils {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case MessageWithFireTime(p: ConsolidatorJobParam, time: Date) =>
      logger.info(s"Consolidating forms $p")
      val senderRef = sender()
      val program: IO[Unit] = (for {
        consolidationResult <- consolidatorService.doConsolidation(p.projectId)
        _                   <- consolidationResult.map(cResult => fileUploaderService.submit(cResult.file, p)).getOrElse(IO.pure(()))
        _ <- addConsolidatorJobData(
              p.projectId,
              ofEpochMilli(time.getTime),
              consolidationResult.flatMap(_.lastObjectId),
              None
            )
      } yield ()).recoverWith {
        case e =>
          logger.error("Failed to consolidate/submit forms", e)
          addConsolidatorJobData(p.projectId, ofEpochMilli(time.getTime), None, Some(e.getMessage))
            .flatMap(_ => IO.raiseError(e))
      }

      val lock = new LockKeeperAutoRenew {
        override val repo: LockRepository = lockRepository
        override val id: String = p.projectId
        override val duration: Duration = Duration.standardMinutes(5)
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
    lastObjectId: Option[BSONObjectID],
    error: Option[String]
  ): IO[Unit] = {
    val consolidatorJobData = ConsolidatorJobData(
      projectId,
      startTime,
      Instant.now(),
      lastObjectId,
      error
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
    lockRepository: LockRepository
  ): Props =
    Props(
      new FormConsolidatorActor(
        consolidatorService,
        fileUploaderService,
        consolidatorJobDataRepository,
        lockRepository))
}
