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

import java.io.File

import common.WSHttpClient
import javax.inject.{ Inject, Singleton }
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FileUploadFrontEndProxy @Inject()(fileUploadConfig: FileUploadConfig, wsHttpClient: WSHttpClient)(
  implicit
  ex: ExecutionContext) {
  def upload(envelopeId: String, fileId: FileId, file: File)(
    implicit
    hc: HeaderCarrier): Future[Either[FileUploadError, Unit]] =
    wsHttpClient
      .POSTFile(
        s"${fileUploadConfig.fileUploadFrontendBaseUrl}/file-upload/upload/envelopes/$envelopeId/files/${fileId.value}",
        file
      )
      .map { response =>
        response.status match {
          case 200 => Right(())
          case other =>
            Left(GenericFileUploadError(
              s"File upload failed [envelopeId=$envelopeId, fileId=${fileId.value}, file=$file, responseStatus=$other, responseBody=${response.body}]"))
        }
      }
      .recover {
        case e => Left(GenericFileUploadError(s"File upload failed [error=$e]"))
      }
}
