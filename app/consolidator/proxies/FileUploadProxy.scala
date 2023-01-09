/*
 * Copyright 2023 HM Revenue & Customs
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

package consolidator.proxies

import common.WSHttpClient
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.http.HeaderNames.LOCATION
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FileUploadProxy @Inject() (fileUploadConfig: FileUploadConfig, wsHttpClient: WSHttpClient)(implicit
  ex: ExecutionContext
) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private lazy val headers = Seq("Csrf-Token" -> "nocheck")
  implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

  def createEnvelope(
    request: CreateEnvelopeRequest
  ): Future[Either[FileUploadError, String]] = {
    logger.info("Creating envelope " + request)
    val EnvelopeIdExtractor = "envelopes/([\\w\\d-]+)$".r.unanchored
    wsHttpClient
      .POST(s"${fileUploadConfig.fileUploadBaseUrl}/file-upload/envelopes", request, headers)
      .map { httpResponse =>
        httpResponse.status match {
          case 201 =>
            httpResponse.header(LOCATION) match {
              case Some(EnvelopeIdExtractor(envelopeId)) =>
                logger.info(s"Create envelope succeeded [envelopeId=$envelopeId]")
                Right(envelopeId)
              case _ =>
                Left(LocationHeaderMissingOrInvalid)
            }
          case other =>
            Left(GenericFileUploadError(s"Create envelope failed [status=$other, body=${httpResponse.body}]"))
        }
      }
      .recover { case e =>
        Left(GenericFileUploadError(s"Create envelope failed [error=$e]"))
      }
  }

  def routeEnvelope(request: RouteEnvelopeRequest): Future[Either[FileUploadError, Unit]] = {
    logger.info("Routing envelope " + request)
    wsHttpClient
      .POST(s"${fileUploadConfig.fileUploadBaseUrl}/file-routing/requests", request, headers)
      .map { httpResponse =>
        httpResponse.status match {
          case 201 =>
            Right(())
          case other =>
            Left(GenericFileUploadError(s"Route envelope failed [status=$other, body=${httpResponse.body}]"))
        }
      }
      .recover { case e =>
        Left(GenericFileUploadError(s"Route envelope failed [error=$e]"))
      }
  }

}
