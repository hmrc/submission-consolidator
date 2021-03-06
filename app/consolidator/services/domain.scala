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

import java.time.{ Instant, ZoneId }

import consolidator.scheduler.UntilTime
import consolidator.scheduler.UntilTime.UntilTime
import ConsolidationFormat.ConsolidationFormat

trait FormConsolidatorParams {
  def projectId: String

  def classificationType: String

  def businessArea: String

  def format: ConsolidationFormat

  def getUntilInstant(currentInstant: Instant): Instant
}

case class ScheduledFormConsolidatorParams(
  projectId: String,
  classificationType: String,
  businessArea: String,
  format: ConsolidationFormat,
  untilTime: UntilTime
) extends FormConsolidatorParams {

  override def getUntilInstant(currentInstant: Instant) = untilTime match {
    case UntilTime.now => currentInstant.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant
    case UntilTime.`previous_day` =>
      currentInstant
        .atZone(ZoneId.systemDefault())
        .minusDays(1)
        .withHour(23)
        .withMinute(59)
        .withSecond(59)
        .withNano(0)
        .toInstant
  }
}

case class ManualFormConsolidatorParams(
  projectId: String,
  classificationType: String,
  businessArea: String,
  format: ConsolidationFormat,
  startInstant: Instant,
  endInstant: Instant
) extends FormConsolidatorParams {
  override def getUntilInstant(currentInstant: Instant): Instant = endInstant
}
