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

package consolidator.services.formatters

import java.time.{ Instant, ZoneId }
import common.Time
import common.UniqueReferenceGenerator.UniqueRef
import consolidator.scheduler.{ FileUpload, UntilTime }
import consolidator.services.MetadataDocumentHelper.buildMetadataDocument
import consolidator.services.{ ConsolidationFormat, MetadataDocumentBuilder, ScheduledFormConsolidatorParams }
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

class MetadataDocumentBuilderSpec extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  "MetadataDocumentBuilder.metaDataDocument" should {
    "return metadata document" in {
      val uniqueRef = UniqueRef("some-unique-id")
      val projectId = "some-project-id"
      val now: Instant = Instant.now()
      implicit val timeInstant: Time[Instant] = () => now
      val zonedDateTime = now.atZone(ZoneId.systemDefault())

      val schedulerFormConsolidatorParams =
        ScheduledFormConsolidatorParams(
          projectId,
          ConsolidationFormat.jsonl,
          FileUpload("some-classification", "some-business-area"),
          UntilTime.now
        )
      val metadataDocument = MetadataDocumentBuilder.metaDataDocument(
        schedulerFormConsolidatorParams.destination.asInstanceOf[FileUpload],
        uniqueRef,
        1
      )
      metadataDocument shouldBe buildMetadataDocument(zonedDateTime, "pdf", "application/pdf", 1)
    }
  }
}
