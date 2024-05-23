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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import consolidator.scheduler.UntilTime.UntilTime
import consolidator.scheduler.{ Destination, UntilTime }
import consolidator.services.ConsolidationFormat.ConsolidationFormat

import java.time.{ Instant, ZoneId }
import java.util.UUID
import scala.annotation.nowarn

trait FormConsolidatorParams {

  def projectId: String

  def format: ConsolidationFormat

  def getUntilInstant(currentInstant: Instant): Instant

  def destination: Destination
}

case class ScheduledFormConsolidatorParams(
  projectId: String,
  format: ConsolidationFormat,
  destination: Destination,
  untilTime: UntilTime
) extends FormConsolidatorParams {

  @nowarn override def getUntilInstant(currentInstant: Instant): Instant =
    untilTime match {
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
  format: ConsolidationFormat,
  destination: Destination,
  startInstant: Instant,
  endInstant: Instant
) extends FormConsolidatorParams {

  override def getUntilInstant(currentInstant: Instant): Instant = endInstant
}

trait UniqueIdGenerator {
  def generate: String
}

object UniqueIdGenerator {
  implicit val uuidStringGenerator: UniqueIdGenerator = new UniqueIdGenerator {
    override def generate: String = UUID.randomUUID().toString
  }
}

object ObjectStoreHelper {

  def toSource(content: ByteString): Source[ByteString, NotUsed] =
    Source.single(content)
}
