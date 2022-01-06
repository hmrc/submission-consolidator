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

package consolidator.repositories

import java.time.Instant

import collector.common.ApplicationError
import play.api.libs.json.{ Format, JsValue, Json, Reads, Writes, __ }
import reactivemongo.bson.BSONObjectID

case class ConsolidatorJobData(
  projectId: String,
  startTimestamp: Instant,
  endTimestamp: Instant,
  lastObjectId: Option[BSONObjectID],
  error: Option[String],
  envelopeId: Option[String],
  id: BSONObjectID = BSONObjectID.generate()
)
object ConsolidatorJobData {
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.{ mongoEntity, objectIdFormats }

  val instantWrites: Writes[Instant] = new Writes[Instant] {
    def writes(datetime: Instant): JsValue = Json.obj("$date" -> datetime.toEpochMilli)
  }

  val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  implicit val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  implicit val formats = mongoEntity {
    Json.format[ConsolidatorJobData]
  }
}

abstract class ConsolidatorJobDataError(message: String) extends ApplicationError(message)
case class GenericConsolidatorJobDataError(message: String) extends ConsolidatorJobDataError(message)
