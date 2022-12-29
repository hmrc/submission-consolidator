/*
 * Copyright 2022 HM Revenue & Customs
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

package consolidator.controllers

import cats.syntax.eq._
import collector.controllers.ErrorHandler
import consolidator.connectors.ObjectStoreConnector
import consolidator.proxies.CallBackNotification
import consolidator.repositories.NotificationStatus.FileProcessed
import consolidator.services.SdesService
import org.slf4j.{ Logger, LoggerFactory }
import play.api.mvc.{ Action, ControllerComponents }
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class SdesCallbackController @Inject() (
  controllerComponents: ControllerComponents,
  objectStoreConnector: ObjectStoreConnector,
  sdesService: SdesService
)(implicit ec: ExecutionContext)
    extends BackendController(controllerComponents) with ErrorHandler {

  private val logger: Logger = LoggerFactory.getLogger(classOf[SdesCallbackController])

  def callback: Action[CallBackNotification] = Action.async(parse.json[CallBackNotification]) { implicit request =>
    val CallBackNotification(responseStatus, fileName, correlationID, responseFailureReason) = request.body
    logger.info(
      s"Received SDES callback for fileName: $fileName, correlationId : $correlationID, status : $responseStatus and failedReason: ${responseFailureReason
        .getOrElse("")} "
    )

    for {
      maybeSdesSubmission <- sdesService.find(correlationID)
      _ <- maybeSdesSubmission match {
             case Some(sdesSubmission) =>
               val updatedSdesSubmission = sdesSubmission.copy(
                 isProcessed = responseStatus === FileProcessed,
                 status = responseStatus,
                 confirmedAt = Some(Instant.now),
                 failureReason = responseFailureReason
               )
               for {
                 _ <- sdesService.save(updatedSdesSubmission)
                 _ <- if (responseStatus === FileProcessed)
                        objectStoreConnector.deleteZipFile(sdesSubmission.envelopeId)
                      else Future.unit
               } yield ()
             case None =>
               Future.failed(new RuntimeException(s"Correlation id [$correlationID] not found in mongo collection"))
           }
    } yield Ok
  }

}
