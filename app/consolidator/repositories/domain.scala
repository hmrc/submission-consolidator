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

package consolidator.repositories

import java.time.LocalDateTime

import play.api.libs.json.Json
import reactivemongo.bson.BSONObjectID

case class ConsolidatorJobData(
  projectId: String,
  startDateTime: LocalDateTime,
  endDateTime: LocalDateTime,
  lastObjectId: Option[BSONObjectID],
  error: Option[String],
  id: BSONObjectID = BSONObjectID.generate()
)
object ConsolidatorJobData {
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.{ mongoEntity, objectIdFormats }
  implicit val formats = mongoEntity {
    Json.format[ConsolidatorJobData]
  }
}

sealed trait ConsolidatorJobDataError
case class GenericConsolidatorJobDataError(message: String) extends ConsolidatorJobDataError
