/*
 * Copyright 2021 HM Revenue & Customs
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

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import akka.util.ByteString
import cats.data.NonEmptyList
import cats.data.NonEmptyList.fromListUnsafe
import cats.effect.{ ContextShift, IO }
import cats.syntax.parallel._
import common.UniqueReferenceGenerator.UniqueRef
import common.{ ContentType, Time, UniqueReferenceGenerator }
import consolidator.IOUtils
import consolidator.proxies._
import consolidator.services.SubmissionService.FileIds
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext

@Singleton
class SubmissionService @Inject()(
  fileUploadProxy: FileUploadProxy,
  fileUploadFrontEndProxy: FileUploadFrontEndProxy,
  uniqueReferenceGenerator: UniqueReferenceGenerator
)(implicit ec: ExecutionContext)
    extends IOUtils with FileUploadSettings {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val SUBMISSION_REF_LENGTH = 12
  private val REPORT_FILE_PATTERN = "report-(\\d+)\\.(.+)".r

  def submit(reportFiles: List[File], params: FormConsolidatorParams)(
    implicit
    time: Time[Instant]): IO[NonEmptyList[String]] = {
    implicit val now: Instant = time.now()
    val zonedDateTime = now.atZone(ZoneId.systemDefault())

    val reportFileList = reportFiles
      .sortBy(f =>
        f.getName match {
          case REPORT_FILE_PATTERN(reportFileId, _) =>
            reportFileId.toInt
          case _ => throw new IllegalArgumentException(s"Report file name not in expected format ${f.getName}")
      })
    assert(reportFileList.nonEmpty, s"Report files should be non-empty")
    logger.info(
      s"Uploading reports to file-upload service [reportFiles=$reportFiles, params=$params]"
    )

    val groupedReportFiles = reportFileList.foldLeft(List(List[File]())) {
      case (acc, reportFile) =>
        if (acc.head.map(_.length()).sum + reportFile.length() > maxReportAttachmentsSize) {
          List(reportFile) :: acc
        } else {
          (reportFile :: acc.head) :: acc.tail
        }
    }

    fromListUnsafe(groupedReportFiles.map(_.reverse).map { reportFiles =>
      logger.info(
        s"Creating envelope and uploading files ${reportFiles.map(_.getName)} for project ${params.projectId}")
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
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
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
                params.format.contentType)
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

      for {
        envelopeId    <- createEnvelope
        submissionRef <- generateSubmissionRef
        fileNamePrefix = s"${submissionRef.ref}-${DATE_FORMAT.format(zonedDateTime)}"
        _ <- uploadMetadata(envelopeId, submissionRef, fileNamePrefix)
        _ <- uploadIForm(envelopeId, fileNamePrefix)
        _ <- uploadReports(envelopeId)
        _ <- routeEnvelope(envelopeId)
      } yield envelopeId
    }).parSequence
  }

  private def generateSubmissionRef =
    liftIO {
      uniqueReferenceGenerator.generate(SUBMISSION_REF_LENGTH)
    }

}

object SubmissionService {

  object FileIds {
    val xmlDocument = FileId("xmlDocument")
    val pdf = FileId("pdf")
  }
}
