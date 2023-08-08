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
import cats.data.NonEmptyList
import cats.data.NonEmptyList.fromListUnsafe
import cats.effect.{ ContextShift, IO }
import cats.syntax.parallel._
import common.UniqueReferenceGenerator.UniqueRef
import common.{ ContentType, Time, UniqueReferenceGenerator }
import consolidator.IOUtils
import consolidator.connectors.ObjectStoreConnector
import consolidator.proxies.ObjectStoreConfig
import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5

import java.io.File
import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext

@Singleton
class SubmissionService @Inject() (
  uniqueReferenceGenerator: UniqueReferenceGenerator,
  objectStoreConnector: ObjectStoreConnector,
  sdesService: SdesService,
  fileUploadService: FileUploadService,
  objectStoreConfig: ObjectStoreConfig
)(implicit ec: ExecutionContext)
    extends IOUtils with FileUploadSettings {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val SUBMISSION_REF_LENGTH = 12
  private val REPORT_FILE_PATTERN = "report-(\\d+)\\.(.+)".r

  def submit(reportFiles: List[File], params: FormConsolidatorParams)(implicit
    time: Time[Instant]
  ): IO[NonEmptyList[String]] = {
    implicit val now: Instant = time.now()
    val zonedDateTime = now.atZone(ZoneId.systemDefault())

    val reportFileList = reportFiles
      .sortBy(f =>
        f.getName match {
          case REPORT_FILE_PATTERN(reportFileId, _) =>
            reportFileId.toInt
          case _ => throw new IllegalArgumentException(s"Report file name not in expected format ${f.getName}")
        }
      )
    assert(reportFileList.nonEmpty, s"Report files should be non-empty")
    logger.info(
      s"Uploading reports to file-upload service [reportFiles=$reportFiles, params=$params]"
    )

    val groupedReportFiles = reportFileList.foldLeft(List(List[File]())) { case (acc, reportFile) =>
      if (acc.head.map(_.length()).sum + reportFile.length() > maxReportAttachmentsSize) {
        List(reportFile) :: acc
      } else {
        (reportFile :: acc.head) :: acc.tail
      }
    }

    fromListUnsafe(groupedReportFiles.map(_.reverse).map { reportFiles =>
      logger.info(
        s"Creating envelope and uploading files ${reportFiles.map(_.getName)} for project ${params.projectId}"
      )

      def uploadMetadata(envelopeId: String, submissionRef: UniqueRef, fileNamePrefix: String) =
        liftIO(
          objectStoreConnector.upload(
            envelopeId,
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
            objectStoreConnector.upload(
              envelopeId,
              file.getName,
              ByteString(Files.readAllBytes(file.toPath)),
              params.format.contentType
            )
          )
        }).parSequence

      def uploadIForm(envelopeId: String, fileNamePrefix: String) =
        liftIO(
          objectStoreConnector.upload(
            envelopeId,
            s"$fileNamePrefix-iform.pdf",
            PDFGenerator.generateIFormPdf(params.projectId),
            ContentType.`application/pdf`
          )
        )

      def zipFiles(envelopeId: String) =
        liftIO(objectStoreConnector.zipFiles(envelopeId))

      def notifySDES(envelopeId: String, submissionRef: String, objWithSummary: ObjectSummaryWithMd5) =
        liftIO(sdesService.notifySDES(envelopeId, submissionRef, objWithSummary))

      if (objectStoreConfig.enableObjectStore) {
        val envelopeId = UniqueIdGenerator.uuidStringGenerator.generate
        logger.info("Creating envelope " + envelopeId)

        for {
          submissionRef <- generateSubmissionRef
          fileNamePrefix = s"${submissionRef.ref}-${DATE_FORMAT.format(zonedDateTime)}"
          _             <- uploadMetadata(envelopeId, submissionRef, fileNamePrefix)
          _             <- uploadIForm(envelopeId, fileNamePrefix)
          _             <- uploadReports(envelopeId)
          objectSummary <- zipFiles(envelopeId)
          _             <- notifySDES(envelopeId, submissionRef.ref, objectSummary)
        } yield envelopeId
      } else {
        fileUploadService.processReportFiles(reportFiles, params, SUBMISSION_REF_LENGTH, zonedDateTime)
      }
    }).parSequence
  }

  private def generateSubmissionRef =
    liftIO {
      uniqueReferenceGenerator.generate(SUBMISSION_REF_LENGTH)
    }

}
