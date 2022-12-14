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

package consolidator.connectors

import common.ContentType
import consolidator.proxies.{ GenericObjectStoreError, ObjectStoreError, SdesConfig }
import consolidator.services.ObjectStoreHelper.toSource
import play.api.Logging
import play.api.mvc._
import uk.gov.hmrc.http.{ HeaderCarrier, UpstreamErrorResponse }
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play._
import akka.actor.ActorSystem
import akka.util.ByteString

@Singleton()
class ObjectStoreConnector @Inject() (
  objectStoreClient: PlayObjectStoreClient,
  cc: ControllerComponents,
  sdesConfig: SdesConfig
)(implicit
  val ec: ExecutionContext,
  system: ActorSystem
) extends BackendController(cc) with Logging {

  private val zipExtension = ".zip"

  private def directory(folderName: String): Path.Directory =
    Path.Directory(s"submission-consolidator/$folderName")

  implicit val hc = HeaderCarrier()

  def upload(
    envelopeId: String,
    fileName: String,
    content: ByteString,
    contentType: ContentType
  ): Future[Either[ObjectStoreError, Unit]] =
    objectStoreClient
      .putObject(
        path = directory(envelopeId).file(fileName),
        content = toSource(content),
        contentType = Some(contentType.value)
      )
      .map(_ => Right(()))
      .recover {
        case UpstreamErrorResponse(message, statusCode, _, _) =>
          val errorMsg = s"Upstream error with status code '$statusCode' and message: $message"
          logger.error(errorMsg)
          Left(GenericObjectStoreError(errorMsg))
        case e: Exception =>
          val errorMsg = s"An error was encountered saving the document. $e"
          logger.error(s"An error was encountered saving the document.", e)
          Left(GenericObjectStoreError(errorMsg))
      }

  def zipFiles(envelopeId: String): Future[Either[ObjectStoreError, ObjectSummaryWithMd5]] =
    objectStoreClient
      .zip(
        from = directory(envelopeId),
        to = Path.Directory(sdesConfig.zipDirectory).file(s"$envelopeId$zipExtension")
      )
      .map(res => Right(res))
      .recover { case e: Exception =>
        val errorMsg = s"An error was encountered zipping the document. $e"
        logger.error(s"An error was encountered zipping the document.", e)
        Left(GenericObjectStoreError(errorMsg))
      }

  def deleteZipFile(envelopeId: String): Future[Unit] =
    objectStoreClient.deleteObject(
      path = Path.Directory(sdesConfig.zipDirectory).file(s"$envelopeId$zipExtension")
    )
}
