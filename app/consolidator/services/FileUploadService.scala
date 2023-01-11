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

package consolidator.services

import akka.util.ByteString
import cats.data.NonEmptyList.fromListUnsafe
import cats.effect.{ ContextShift, IO }
import cats.implicits.catsSyntaxParallelSequence
import common.UniqueReferenceGenerator.UniqueRef
import common.{ ContentType, Time, UniqueReferenceGenerator }
import consolidator.IOUtils
import consolidator.proxies._
import consolidator.services.FileUploadService.FileIds

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZonedDateTime }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext

@Singleton
class FileUploadService @Inject() (
  fileUploadProxy: FileUploadProxy,
  fileUploadFrontEndProxy: FileUploadFrontEndProxy,
  uniqueReferenceGenerator: UniqueReferenceGenerator
)(implicit ec: ExecutionContext)
    extends IOUtils with FileUploadSettings {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

  def processReportFiles(
    reportFiles: List[File],
    params: FormConsolidatorParams,
    submissionRefLen: Int,
    zonedDateTime: ZonedDateTime
  )(implicit
    time: Time[Instant]
  ) = {
    implicit val now: Instant = time.now()

    val createEnvelopeRequest = CreateEnvelopeRequest(
      consolidator.proxies.Metadata("gform"),
      Constraints(
        reportFiles.size + 2, // +2 for metadata xml and iform pdf
        (maxSizeBytes / BYTES_IN_1_MB) + "MB",
        (maxPerFileBytes / BYTES_IN_1_MB) + "MB",
        List(
          "text/plain",
          "text/csv",
          "application/pdf",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ),
        false
      )
    )

    def createEnvelope =
      liftIO(fileUploadProxy.createEnvelope(createEnvelopeRequest))

    def uploadMetadata(envelopeId: String, submissionRef: UniqueRef, fileNamePrefix: String) =
      liftIO(
        fileUploadFrontEndProxy.upload(
          envelopeId,
          FileIds.xmlDocument,
          s"$fileNamePrefix-metadata.xml",
          ByteString(
            MetadataDocumentBuilder.metaDataDocument(params, submissionRef, reportFiles.length).toXml
          ),
          ContentType.`application/xml`
        )
      )

    def uploadReports(envelopeId: String) =
      fromListUnsafe(reportFiles.map { file =>
        liftIO(
          fileUploadFrontEndProxy
            .upload(
              envelopeId,
              FileId(file.getName.substring(0, file.getName.lastIndexOf("."))),
              file,
              params.format.contentType
            )
        )
      }).parSequence

    def uploadIForm(envelopeId: String, fileNamePrefix: String) =
      liftIO(
        fileUploadFrontEndProxy.upload(
          envelopeId,
          FileIds.pdf,
          s"$fileNamePrefix-iform.pdf",
          PDFGenerator.generateIFormPdf(params.projectId),
          ContentType.`application/pdf`
        )
      )

    def routeEnvelope(envelopeId: String) =
      liftIO(fileUploadProxy.routeEnvelope(RouteEnvelopeRequest(envelopeId, "dfs", "DMS")))

    def generateSubmissionRef =
      liftIO {
        uniqueReferenceGenerator.generate(submissionRefLen)
      }

    for {
      envelopeId    <- createEnvelope
      submissionRef <- generateSubmissionRef
      fileNamePrefix = s"${submissionRef.ref}-${DATE_FORMAT.format(zonedDateTime)}"
      _ <- uploadMetadata(envelopeId, submissionRef, fileNamePrefix)
      _ <- uploadIForm(envelopeId, fileNamePrefix)
      _ <- uploadReports(envelopeId)
      _ <- routeEnvelope(envelopeId)
    } yield envelopeId
  }
}

object FileUploadService {
  object FileIds {
    val xmlDocument = FileId("xmlDocument")
    val pdf = FileId("pdf")
  }
}
