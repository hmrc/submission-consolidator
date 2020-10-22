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

package consolidator.scheduler

import java.time.Instant

import consolidator.scheduler.UntilTime._
import consolidator.services.{ ManualFormConsolidatorParams, ScheduledFormConsolidatorParams }
import consolidator.services.formatters.ConsolidationFormat
import consolidator.services.formatters.ConsolidationFormat.ConsolidationFormat
import play.api.libs.functional.syntax._
import play.api.libs.json.{ Format, Json, __ }

case class ConsolidatorJobConfigParam(
  projectId: String,
  classificationType: String,
  businessArea: String,
  format: ConsolidationFormat,
  untilTime: UntilTime) {
  def toScheduledFormConsolidatorParams: ScheduledFormConsolidatorParams =
    ScheduledFormConsolidatorParams(projectId, classificationType, businessArea, format, untilTime)

  def toManualFormConsolidatorParams(startInstant: Instant, endInstant: Instant): ManualFormConsolidatorParams =
    ManualFormConsolidatorParams(projectId, classificationType, businessArea, format, startInstant, endInstant)
}
object ConsolidatorJobConfigParam {
  val writes = Json.writes[ConsolidatorJobConfigParam]
  val reads = (
    (__ \ "projectId").read[String] and
      (__ \ "classificationType").read[String] and
      (__ \ "businessArea").read[String] and
      (__ \ "format").readWithDefault[ConsolidationFormat](ConsolidationFormat.jsonl) and
      (__ \ "untilTime").readWithDefault[UntilTime](UntilTime.now)
  )(ConsolidatorJobConfigParam.apply _)

  implicit val formats = Format(reads, writes)
}
case class ConsolidatorJobConfig(id: String, cron: String, params: ConsolidatorJobConfigParam)
object ConsolidatorJobConfig {
  implicit val formats = Json.format[ConsolidatorJobConfig]
}
