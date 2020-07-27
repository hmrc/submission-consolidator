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

package collector.repositories

import java.time.Instant

import play.api.libs.json.{ Format, JsValue, Json, Reads, Writes, __ }
import reactivemongo.bson.BSONObjectID
import collector.common.ApplicationError

case class FormField(id: String, value: String)
object FormField {
  implicit val formats: Format[FormField] = Json.format[FormField]
}

case class Form(
  submissionRef: String,
  formId: String,
  templateId: String,
  customerId: String,
  submissionTimestamp: Instant,
  formData: List[FormField],
  id: BSONObjectID = BSONObjectID.generate
)

object Form {
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.{ mongoEntity, objectIdFormats }

  val instantWrites: Writes[Instant] = new Writes[Instant] {
    def writes(datetime: Instant): JsValue = Json.obj("$date" -> datetime.toEpochMilli)
  }

  val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  implicit val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  implicit val formats: Format[Form] = mongoEntity {
    Json.format[Form]
  }
}

sealed trait FormError extends ApplicationError {
  def message: String
}
case class DuplicateSubmissionRef(submissionRef: String, message: String) extends FormError
case class MongoUnavailable(message: String) extends FormError
case class MongoGenericError(message: String) extends FormError
