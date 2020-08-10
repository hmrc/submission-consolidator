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
import cats.effect.IO
import common.Time
import consolidator.{ IOUtils, services }
import consolidator.proxies._
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.SubmissionService.{ FileIds, SubmissionRef }
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext

@Singleton
class SubmissionService @Inject()(
  fileUploadProxy: FileUploadProxy,
  fileUploadFrontEndProxy: FileUploadFrontEndProxy,
  submissionRefGenerator: SubmissionRefGenerator
)(implicit ec: ExecutionContext)
    extends IOUtils {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  def submit(reportFile: File, config: ConsolidatorJobParam)(implicit time: Time[Instant]): IO[Unit] = {
    logger.info(s"Submitting forms file to DMS [file=$reportFile, config=$config]")
    val createEnvelopeRequest = CreateEnvelopeRequest(
      consolidator.proxies.Metadata("submission-consolidator"),
      Constraints(2, "25MB", "10MB", List("text/plain"), false)
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
          ByteString(MetadataXml.toXml(metaDataDocument(config, submissionRef, reconciliationId)))
        )
      )

    def uploadReport(envelopeId: String) =
      liftIO(fileUploadFrontEndProxy.upload(envelopeId, FileIds.report, reportFile))

    def routeEnvelope(envelopeId: String) =
      liftIO(fileUploadProxy.routeEnvelope(RouteEnvelopeRequest(envelopeId, "submission-consolidator", "DMS")))

    for {
      envelopeId <- createEnvelope
      _          <- uploadMetadata(envelopeId)
      _          <- uploadReport(envelopeId)
      _          <- routeEnvelope(envelopeId)
    } yield ()
  }

  private def metaDataDocument(config: ConsolidatorJobParam, submissionRef: SubmissionRef, reconciliationId: String) =
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
            Attribute("attachment_count", "int", List("2"))
          )
        )
      )
    )
}

object SubmissionService {

  case class SubmissionRef(value: String) extends AnyVal

  object FileIds {
    val report = FileId("report")
    val xmlDocument = FileId("xmlDocument")
  }
}
