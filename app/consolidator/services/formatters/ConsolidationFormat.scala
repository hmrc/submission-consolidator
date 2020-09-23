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

import play.api.libs.json.Json

object ConsolidationFormat extends Enumeration {
  type ConsolidationFormat = Value
  val csv, jsonl = Value

  implicit val formats = Json.formatEnum(ConsolidationFormat)

  implicit class ConsolidationFormatOps(consolidationFormat: ConsolidationFormat) {
    def metadataDocumentBuilder: MetadataDocumentBuilder = consolidationFormat match {
      case ConsolidationFormat.csv   => CSVMetadataDocumentBuilder
      case ConsolidationFormat.jsonl => JSONLineMetadaDocumentBuilder
    }
  }
}
