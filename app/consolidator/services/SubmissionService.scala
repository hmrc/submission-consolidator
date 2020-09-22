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
import common.{ Time, UniqueReferenceGenerator }
import consolidator.IOUtils
import consolidator.proxies._
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.SubmissionService.FileIds
import consolidator.services.formatters.{ CSVMetadataDocumentBuilder, ConsolidationFormat, JSONLineMetadaDocumentBuilder }
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
  private val REPORT_FILE_PATTERN = "report-(\\d+)\\.txt".r

  def submit(reportFiles: List[File], config: ConsolidatorJobParam)(
    implicit time: Time[Instant]): IO[NonEmptyList[String]] = {

    val now: Instant = time.now()

    val reportFileList = reportFiles
      .sortBy(_.getName match {
        case REPORT_FILE_PATTERN(reportFileId) => reportFileId.toInt
        case _                                 => 0
      })
    assert(reportFileList.nonEmpty, s"Report files should be non-empty")
    logger.info(
      s"Uploading reports to file-upload service [reportFiles=$reportFiles, config=$config]"
    )

    fromListUnsafe(reportFileList.grouped(maxReportAttachments).toList.map { reportFiles =>
      logger.info(s"Creating envelope and uploading files ${reportFiles.map(_.getName)}")
      val createEnvelopeRequest = CreateEnvelopeRequest(
        consolidator.proxies.Metadata("gform"),
        Constraints(
          reportFiles.size + 1, // +1 for metadata xml
          (maxSizeBytes / BYTES_IN_1_MB) + "MB",
          (maxPerFileBytes / BYTES_IN_1_MB) + "MB",
          List("text/plain", "text/csv"),
          false
        )
      )

      def createEnvelope =
        liftIO(fileUploadProxy.createEnvelope(createEnvelopeRequest))

      def uploadMetadata(envelopeId: String, submissionRef: UniqueRef) = {
        val zonedDateTime = now.atZone(ZoneId.systemDefault())
        val fileNamePrefix = s"${submissionRef.ref}-${DATE_FORMAT.format(zonedDateTime)}"
        val metadataDocumentBuilder = config.format match {
          case ConsolidationFormat.csv   => CSVMetadataDocumentBuilder
          case ConsolidationFormat.jsonl => JSONLineMetadaDocumentBuilder
        }
        liftIO(
          fileUploadFrontEndProxy.upload(
            envelopeId,
            FileIds.xmlDocument,
            s"$fileNamePrefix-metadata.xml",
            ByteString(metadataDocumentBuilder.metaDataDocument(config, submissionRef, reportFiles.length).toXml)
          )
        )
      }

      def uploadReports(envelopeId: String) =
        fromListUnsafe(reportFiles.map { file =>
          liftIO(
            fileUploadFrontEndProxy
              .upload(envelopeId, FileId(file.getName.substring(0, file.getName.lastIndexOf("."))), file)
          )
        }).parSequence

      def routeEnvelope(envelopeId: String) =
        liftIO(fileUploadProxy.routeEnvelope(RouteEnvelopeRequest(envelopeId, "dfs", "DMS")))

      for {
        envelopeId    <- createEnvelope
        submissionRef <- generateSubmissionRef
        _             <- uploadMetadata(envelopeId, submissionRef)
        _             <- uploadReports(envelopeId)
        _             <- routeEnvelope(envelopeId)
      } yield envelopeId
    }).parSequence
  }

  private def generateSubmissionRef = liftIO {
    uniqueReferenceGenerator.generate(SUBMISSION_REF_LENGTH)
  }
}

object SubmissionService {

  object FileIds {
    val xmlDocument = FileId("xmlDocument")
  }
}
