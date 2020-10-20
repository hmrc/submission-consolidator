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

package consolidator.services.formatters

import java.time.{ Instant, ZoneId }

import common.Time
import common.UniqueReferenceGenerator.UniqueRef
import consolidator.scheduler.UntilTime
import consolidator.services.MetadataDocumentHelper.buildMetadataDocument
import consolidator.services.ScheduledFormConsolidatorParams
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

class MetadataDocumentBuilderSpec extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  "CSVMetadataDocumentBuilder.metaDataDocument" should {
    "return metadata document for all formats" in {

      val uniqueRef = UniqueRef("some-unique-id")
      val projectId = "some-project-id"
      val now: Instant = Instant.now()
      implicit val timeInstant: Time[Instant] = () => now
      val zonedDateTime = now.atZone(ZoneId.systemDefault())

      val testData = Table(
        (
          "metadataDocumentBuilder",
          "consolidationFormat",
          "expectedFormat",
          "expectedMimetype"
        ),
        (CSVMetadataDocumentBuilder, ConsolidationFormat.csv, "pdf", "application/pdf"),
        (
          JSONLineMetadaDocumentBuilder,
          ConsolidationFormat.jsonl,
          "pdf",
          "application/pdf"
        )
      )

      forAll(testData) { (metadataDocumentBuilder, consolidationFormat, expectedFormat, expectedMimetype) =>
        val schedulerFormConsolidatorParams =
          ScheduledFormConsolidatorParams(
            projectId,
            "some-classification",
            "some-business-area",
            consolidationFormat,
            UntilTime.now)
        val metadataDocument = metadataDocumentBuilder.metaDataDocument(schedulerFormConsolidatorParams, uniqueRef, 1)
        metadataDocument shouldBe buildMetadataDocument(zonedDateTime, expectedFormat, expectedMimetype, 1)
      }
    }
  }

}
