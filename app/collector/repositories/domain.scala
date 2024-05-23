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

package collector.repositories

import collector.common.ApplicationError
import julienrf.json.derived
import org.bson.types.ObjectId
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.{ MongoFormats, MongoJavatimeFormats }

import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

case class FormField(id: String, value: String)
object FormField {
  implicit val formats: Format[FormField] = Json.format[FormField]
}

case class Form(
  submissionRef: String,
  projectId: String,
  templateId: String,
  customerId: String,
  submissionTimestamp: Instant,
  formData: List[FormField],
  _id: ObjectId = ObjectId.get()
)

object Form {

  val DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))

  implicit val format: OFormat[Form] = {
    implicit val instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    implicit val oidFormat: Format[ObjectId] = MongoFormats.objectIdFormat
    derived.oformat()
  }
}

case class AggregateResult(_id: String)

object AggregateResult {
  implicit val format: OFormat[AggregateResult] =
    derived.oformat()
}

abstract class FormError(message: String) extends ApplicationError(message)
case class DuplicateSubmissionRef(submissionRef: String, message: String) extends FormError(message)
case class MongoUnavailable(message: String) extends FormError(message)
case class MongoGenericError(message: String) extends FormError(message)
