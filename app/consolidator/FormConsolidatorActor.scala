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

import akka.actor.{ Actor, Props }
import cats.effect.IO
import consolidator.FormConsolidatorActor.OK
import consolidator.dms.FileUploaderService
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.ConsolidatorService
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext

class FormConsolidatorActor(consolidatorService: ConsolidatorService, fileUploaderService: FileUploaderService)
    extends Actor {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case p: ConsolidatorJobParam =>
      logger.info(s"Consolidating forms $p")
      val senderRef = sender()
      val program = for {
        consolidatedFile <- consolidatorService.doConsolidation(p.projectId)
        _                <- consolidatedFile.map(f => fileUploaderService.upload(f).map(Some(_))).getOrElse(IO.pure(()))
      } yield ()
      program.unsafeRunAsync {
        case Left(error) => senderRef ! error
        case Right(_)    => senderRef ! OK
      }
  }
}

object FormConsolidatorActor {

  sealed trait Status
  case object OK extends Status

  def props(consolidatorService: ConsolidatorService, fileUploaderService: FileUploaderService): Props =
    Props(new FormConsolidatorActor(consolidatorService, fileUploaderService))
}
