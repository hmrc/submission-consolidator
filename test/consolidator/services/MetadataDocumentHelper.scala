/*
 * Copyright 2022 HM Revenue & Customs
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

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object MetadataDocumentHelper {

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
  private val DDMMYYYYHHMMSS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

  def buildMetadataDocument(
    zonedDateTime: ZonedDateTime,
    expectedFormat: String,
    expectedMimetype: String,
    expectedAttachments: Int
  ) = {
    val expected = MetadataDocument(
      Documents(
        Document(
          Header(
            "some-unique-id",
            expectedFormat,
            expectedMimetype,
            true,
            "dfs",
            "DMS",
            s"some-unique-id-${DATE_TIME_FORMAT.format(zonedDateTime)}"
          ),
          Metadata(
            List(
              Attribute("hmrc_time_of_receipt", "time", List(DDMMYYYYHHMMSS.format(zonedDateTime))),
              Attribute("time_xml_created", "time", List(DDMMYYYYHHMMSS.format(zonedDateTime))),
              Attribute("submission_reference", "string", List("some-unique-id")),
              Attribute("form_id", "string", List("collatedData")),
              Attribute("submission_mark", "string", List("AUDIT_SERVICE")),
              Attribute("cas_key", "string", List("AUDIT_SERVICE")),
              Attribute("number_pages", "int", List("1")),
              Attribute("customer_id", "string", List(s"Report-${DATE_FORMAT.format(zonedDateTime)}")),
              Attribute("classification_type", "string", List("some-classification")),
              Attribute("business_area", "string", List("some-business-area")),
              Attribute("attachment_count", "int", List(expectedAttachments.toString)),
              Attribute("source", "string", List("dfs"))
            )
          )
        )
      )
    )
    expected
  }
}
