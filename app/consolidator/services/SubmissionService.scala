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

import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import akka.util.ByteString
import cats.data.NonEmptyList
import cats.data.NonEmptyList.fromListUnsafe
import cats.effect.{ ContextShift, IO }
import cats.syntax.parallel._
import common.Time
import consolidator.proxies._
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.SubmissionService.{ FileIds, SubmissionRef }
import consolidator.{ IOUtils, services }
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext

@Singleton
class SubmissionService @Inject()(
  fileUploadProxy: FileUploadProxy,
  fileUploadFrontEndProxy: FileUploadFrontEndProxy,
  submissionRefGenerator: SubmissionRefGenerator
)(implicit ec: ExecutionContext)
    extends IOUtils with FileUploadSettings {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  private val REPORT_FILE_PATTERN = "report-(\\d+)\\.txt".r

  def submit(reportFilesPath: Path, config: ConsolidatorJobParam)(
    implicit
    time: Time[Instant]): IO[NonEmptyList[String]] = {
    val reportFileList = reportFilesPath.toFile
      .listFiles()
      .toList
      .sortBy(_.getName match {
        case REPORT_FILE_PATTERN(reportFileId) => reportFileId.toInt
        case _                                 => 0
      })
    logger.info(
      s"Uploading reports to file-upload service [reportFilesPath=$reportFilesPath, config=$config]"
    )
    assert(reportFileList.nonEmpty, s"Report files directory should be non-empty $reportFilesPath")

    fromListUnsafe(reportFileList.grouped(maxReportAttachments).toList.map { reportFiles =>
      logger.info(s"Creating envelope and uploading files ${reportFiles.map(_.getName)}")
      val createEnvelopeRequest = CreateEnvelopeRequest(
        consolidator.proxies.Metadata("submission-consolidator"),
        Constraints(
          reportFiles.size + 1, // +1 for metadata xml
          (maxSizeBytes / BYTES_IN_1_MB) + "MB",
          (maxPerFileBytes / BYTES_IN_1_MB) + "MB",
          List("text/plain"),
          false
        )
      )
      val submissionRef = submissionRefGenerator.generate
      val zonedDateTime = time.now().atZone(ZoneId.systemDefault())
      val fileNamePrefix = s"${submissionRef.value}-${DATE_FORMAT.format(zonedDateTime)}"
      val reconciliationId = s"${submissionRef.value}-${DATE_TIME_FORMAT.format(zonedDateTime)}"

      def createEnvelope =
        liftIO(fileUploadProxy.createEnvelope(createEnvelopeRequest))

      def uploadMetadata(envelopeId: String) =
        liftIO(
          fileUploadFrontEndProxy.upload(
            envelopeId,
            FileIds.xmlDocument,
            s"$fileNamePrefix-metadata.xml",
            ByteString(MetadataXml.toXml(metaDataDocument(config, submissionRef, reconciliationId, reportFiles.size)))
          )
        )

      def uploadReports(envelopeId: String) =
        fromListUnsafe(reportFiles.map { file =>
          liftIO(
            fileUploadFrontEndProxy
              .upload(envelopeId, FileId(file.getName.replace(".txt", "")), file)
          )
        }).parSequence

      def routeEnvelope(envelopeId: String) =
        liftIO(fileUploadProxy.routeEnvelope(RouteEnvelopeRequest(envelopeId, "submission-consolidator", "DMS")))

      for {
        envelopeId <- createEnvelope
        _          <- uploadMetadata(envelopeId)
        _          <- uploadReports(envelopeId)
        _          <- routeEnvelope(envelopeId)
      } yield envelopeId
    }).parSequence
  }

  private def metaDataDocument(
    config: ConsolidatorJobParam,
    submissionRef: SubmissionRef,
    reconciliationId: String,
    attachmentCount: Int
  ) =
    Documents(
      Document(
        Header(
          submissionRef.value,
          "jsonlines",
          "text/plain",
          true,
          "dfs",
          "DMS",
          reconciliationId
        ),
        services.Metadata(
          List(
            Attribute("classification_type", "string", List(config.classificationType)),
            Attribute("business_area", "string", List(config.businessArea)),
            Attribute("attachment_count", "int", List(attachmentCount.toString))
          )
        )
      )
    )
}

object SubmissionService {

  case class SubmissionRef(value: String) extends AnyVal

  object FileIds {
    val xmlDocument = FileId("xmlDocument")
  }
}
