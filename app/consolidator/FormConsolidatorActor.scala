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
import java.util.Date

import akka.actor.{ Actor, Props }
import cats.effect.IO
import consolidator.FormConsolidatorActor.OK
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.{ ConsolidatorService, SubmissionService }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext
import cats.implicits._
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import reactivemongo.bson.BSONObjectID

class FormConsolidatorActor(
  consolidatorService: ConsolidatorService,
  fileUploaderService: SubmissionService,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository
) extends Actor with IOUtils {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case MessageWithFireTime(p: ConsolidatorJobParam, time: Date) =>
      logger.info(s"Consolidating forms $p")
      val senderRef = sender()
      val startTimestamp = Instant.ofEpochMilli(time.getTime)
      val program: IO[Unit] = (for {
        consolidationResult <- consolidatorService.doConsolidation(p.projectId)
        _                   <- consolidationResult.map(cResult => fileUploaderService.submit(cResult.file, p)).getOrElse(IO.pure(()))
        _                   <- addConsolidatorJobData(p.projectId, startTimestamp, consolidationResult.flatMap(_.lastObjectId), None)
      } yield ()).recoverWith {
        case e =>
          logger.error("Failed to consolidate/submit forms", e)
          addConsolidatorJobData(p.projectId, startTimestamp, None, Some(e.getMessage))
            .flatMap(_ => IO.raiseError(e))
      }

      program.unsafeRunAsync {
        case Left(error) => senderRef ! error
        case Right(_)    => senderRef ! OK
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
  case object OK extends Status

  def props(
    consolidatorService: ConsolidatorService,
    fileUploaderService: SubmissionService,
    consolidatorJobDataRepository: ConsolidatorJobDataRepository
  ): Props =
    Props(new FormConsolidatorActor(consolidatorService, fileUploaderService, consolidatorJobDataRepository))
}
