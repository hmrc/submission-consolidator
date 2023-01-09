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

package consolidator.scheduler

import common.JsonFormat._
import consolidator.scheduler.UntilTime._
import consolidator.services.ConsolidationFormat.ConsolidationFormat
import consolidator.services.{ ConsolidationFormat, ManualFormConsolidatorParams, ScheduledFormConsolidatorParams }
import julienrf.json.derived
import play.api.libs.functional.syntax._
import play.api.libs.json.{ Format, Json, __ }

import java.net.URI
import java.time.Instant

case class ConsolidatorJobConfigParam(
  projectId: String,
  format: ConsolidationFormat,
  untilTime: UntilTime,
  destination: Destination
) {
  def toScheduledFormConsolidatorParams: ScheduledFormConsolidatorParams =
    ScheduledFormConsolidatorParams(projectId, format, destination, untilTime)

  def toManualFormConsolidatorParams(startInstant: Instant, endInstant: Instant): ManualFormConsolidatorParams =
    ManualFormConsolidatorParams(projectId, format, destination, startInstant, endInstant)
}
object ConsolidatorJobConfigParam {
  val writes = Json.writes[ConsolidatorJobConfigParam]
  val reads = (
    (__ \ "projectId").read[String] and
      (__ \ "classificationType").readNullable[String] and
      (__ \ "businessArea").readNullable[String] and
      (__ \ "format").readWithDefault[ConsolidationFormat](ConsolidationFormat.jsonl) and
      (__ \ "untilTime").readWithDefault[UntilTime](UntilTime.now) and
      (__ \ "destination").readNullable[String] and
      (__ \ "s3Endpoint").readNullable[URI] and
      (__ \ "bucket").readNullable[String]
  ) { (projectId, classificationType, businessArea, format, untilTime, destination, s3Endpoint, bucket) =>
    ConsolidatorJobConfigParam(
      projectId,
      format,
      untilTime,
      destination match {
        case None | Some("fileUpload") =>
          assert(classificationType.isDefined)
          assert(businessArea.isDefined)
          FileUpload(classificationType.get, businessArea.get)
        case Some("s3") =>
          assert(s3Endpoint.isDefined)
          assert(bucket.isDefined)
          S3(s3Endpoint.get, bucket.get)
        case Some(other) => throw new IllegalArgumentException(s"destination $other not supported")
      }
    )
  }

  implicit val formats = Format(reads, writes)
}
case class ConsolidatorJobConfig(id: String, cron: String, params: ConsolidatorJobConfigParam)
object ConsolidatorJobConfig {
  implicit val formats = Json.format[ConsolidatorJobConfig]
}

sealed trait Destination

case class FileUpload(classificationType: String, businessArea: String) extends Destination
case class S3(s3Endpoint: URI, bucket: String) extends Destination

object Destination {
  implicit val format: Format[Destination] = derived.oformat()
}
