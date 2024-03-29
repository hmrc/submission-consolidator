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

import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }
import common.Time
import common.UniqueReferenceGenerator.UniqueRef
import consolidator.scheduler.FileUpload
import consolidator.services
import shapeless.syntax.typeable._

trait MetadataDocumentBuilder {

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
  private val DDMMYYYYHHMMSS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

  def metaDataDocument(params: FormConsolidatorParams, submissionRef: UniqueRef, attachmentCount: Int)(implicit
    time: Time[Instant]
  ): MetadataDocument =
    buildMetaDataDocument(params, submissionRef, attachmentCount, "pdf", "application/pdf")

  protected def buildMetaDataDocument(
    params: FormConsolidatorParams,
    submissionRef: UniqueRef,
    attachmentCount: Int,
    format: String,
    mime: String
  )(implicit time: Time[Instant]) = {
    val fileUploadDestination = params.destination.cast[FileUpload]
    assert(fileUploadDestination.isDefined, "FormConsolidatorParams destination should be FileUpload type")

    val zonedDateTime = time.now().atZone(ZoneId.systemDefault())
    MetadataDocument(
      Documents(
        Document(
          Header(
            submissionRef.ref,
            format,
            mime,
            true,
            "dfs",
            "DMS",
            s"${submissionRef.ref}-${DATE_TIME_FORMAT.format(zonedDateTime)}"
          ),
          services.Metadata(
            List(
              Attribute("hmrc_time_of_receipt", "time", List(DDMMYYYYHHMMSS.format(zonedDateTime))),
              Attribute("time_xml_created", "time", List(DDMMYYYYHHMMSS.format(zonedDateTime))),
              Attribute("submission_reference", "string", List(submissionRef.ref)),
              Attribute("form_id", "string", List("collatedData")),
              Attribute("submission_mark", "string", List("AUDIT_SERVICE")),
              Attribute("cas_key", "string", List("AUDIT_SERVICE")),
              Attribute("number_pages", "int", List("1")),
              Attribute("customer_id", "string", List(s"Report-${DATE_FORMAT.format(zonedDateTime)}")),
              Attribute("classification_type", "string", List(fileUploadDestination.get.classificationType)),
              Attribute("business_area", "string", List(fileUploadDestination.get.businessArea)),
              Attribute("attachment_count", "int", List(attachmentCount.toString)),
              Attribute("source", "string", List("dfs"))
            )
          )
        )
      )
    )
  }
}

object MetadataDocumentBuilder extends MetadataDocumentBuilder
