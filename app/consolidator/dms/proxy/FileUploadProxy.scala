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

package consolidator.dms.proxy

import common.WSHttpClient
import javax.inject.{ Inject, Singleton }
import play.api.http.HeaderNames.LOCATION
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FileUploadProxy @Inject()(fileUploadConfig: FileUploadConfig, wsHttpClient: WSHttpClient)(
  implicit
  ex: ExecutionContext) {
  private lazy val headers = Seq("Csrf-Token" -> "nocheck")

  def createEnvelope(
    request: CreateEnvelopeRequest
  )(implicit hc: HeaderCarrier): Future[Either[FileUploadError, String]] = {
    val EnvelopeIdExtractor = "envelopes/([\\w\\d-]+)$".r.unanchored
    wsHttpClient
      .POST(s"${fileUploadConfig.fileUploadBaseUrl}/file-upload/envelopes", request, headers)
      .map { httpResponse =>
        httpResponse.status match {
          case 201 =>
            httpResponse.header(LOCATION) match {
              case Some(EnvelopeIdExtractor(envelopeId)) => Right(envelopeId)
              case _                                     => Left(LocationHeaderMissingOrInvalid)
            }
          case other =>
            Left(GenericFileUploadError(s"Create envelope failed [status=$other, body=${httpResponse.body}]"))
        }
      }
      .recover {
        case e => Left(GenericFileUploadError(s"Create envelope failed [error=$e]"))
      }
  }

  def routeEnvelope(request: RouteEnvelopeRequest)(implicit hc: HeaderCarrier): Future[Either[FileUploadError, Unit]] =
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
      .recover {
        case e => Left(GenericFileUploadError(s"Route envelope failed [error=$e]"))
      }

}
