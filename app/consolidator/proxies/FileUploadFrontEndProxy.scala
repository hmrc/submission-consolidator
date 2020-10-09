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

package consolidator.proxies

import java.io.File

import akka.util.ByteString
import common.{ ContentType, WSHttpClient }
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FileUploadFrontEndProxy @Inject()(fileUploadConfig: FileUploadConfig, wsHttpClient: WSHttpClient)(
  implicit
  ex: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def upload(
    envelopeId: String,
    fileId: FileId,
    file: File,
    contentType: ContentType): Future[Either[FileUploadError, Unit]] = {
    logger.info(s"Uploading file [envelopeId=$envelopeId, fileId=$fileId, file=$file]")
    wsHttpClient
      .POSTFile(
        s"${fileUploadConfig.fileUploadFrontendBaseUrl}/file-upload/upload/envelopes/$envelopeId/files/${fileId.value}",
        file,
        contentType
      )
      .map { response =>
        response.status match {
          case 200 => Right(())
          case other =>
            Left(
              GenericFileUploadError(
                s"File upload failed [envelopeId=$envelopeId, fileId=${fileId.value}, file=$file, responseStatus=$other, responseBody=${response.body}]"
              )
            )
        }
      }
      .recover {
        case e => Left(GenericFileUploadError(s"File upload failed [error=$e]"))
      }
  }

  def upload(
    envelopeId: String,
    fileId: FileId,
    fileName: String,
    body: ByteString,
    contentType: ContentType): Future[Either[FileUploadError, Unit]] = {
    logger.info(s"Uploading file [envelopeId=$envelopeId, fileId=$fileId, fileName=$fileName]")
    wsHttpClient
      .POSTFile(
        s"${fileUploadConfig.fileUploadFrontendBaseUrl}/file-upload/upload/envelopes/$envelopeId/files/${fileId.value}",
        fileName,
        body,
        contentType
      )
      .map { response =>
        response.status match {
          case 200 => Right(())
          case other =>
            Left(
              GenericFileUploadError(
                s"File upload failed [envelopeId=$envelopeId, fileId=${fileId.value}, fileName=$fileName, responseStatus=$other, responseBody=${response.body}]"
              )
            )
        }
      }
      .recover {
        case e => Left(GenericFileUploadError(s"File upload failed [error=$e]"))
      }
  }
}
