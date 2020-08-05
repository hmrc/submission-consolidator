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

package collector.controllers

import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter.ISO_DATE_TIME

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json.{ JsPath, Reads }
import play.api.libs.json._
import collector.repositories.{ Form, FormField }
import collector.common.ApplicationError

import scala.util.Try

case class APIFormField(id: String, value: String)

object APIFormField {
  implicit val reads: Reads[APIFormField] =
    ((JsPath \ "id").read[String](minLength[String](1)) and
      (JsPath \ "value").read[String](minLength[String](1)))((id, value) => APIFormField(id, value))
}

case class APIForm(
  submissionRef: String,
  projectId: String,
  templateId: String,
  customerId: String,
  submissionTimestamp: String,
  formData: List[APIFormField]
)

object APIForm {

  private def parseAsLocalDateTime(value: String) =
    LocalDateTime.parse(value, ISO_DATE_TIME)

  implicit val reads: Reads[APIForm] = (
    (JsPath \ "submissionRef").read[String](
      pattern(
        "^([A-Z0-9]{4})-([A-Z0-9]{4})-([A-Z0-9]{4})$".r,
        "Must confirm to the format XXXX-XXXX-XXXX, where X is a upper-case alphabet or a number"
      )
    ) and
      (JsPath \ "projectId").read[String](minLength[String](1)) and
      (JsPath \ "templateId").read[String](minLength[String](1)) and
      (JsPath \ "customerId").read[String](minLength[String](1)) and
      (JsPath \ "submissionTimestamp").read[String](
        filter[String](JsonValidationError("Must confirm to ISO-8601 date-time format YYYY-MM-DD'T'HH:mm:ssZ"))(value =>
          Try(parseAsLocalDateTime(value)).isSuccess)
      ) and
      (JsPath \ "formData").readNullableWithDefault[List[APIFormField]](None)
  )((submissionRef, formId, templateId, customerId, submissionTimestamp, formData) =>
    APIForm(submissionRef, formId, templateId, customerId, submissionTimestamp, formData.getOrElse(List.empty)))

  implicit class APIFormOps(apiForm: APIForm) {
    def toForm =
      Form(
        apiForm.submissionRef,
        apiForm.projectId,
        apiForm.templateId,
        apiForm.customerId,
        parseAsLocalDateTime(apiForm.submissionTimestamp).toInstant(UTC),
        apiForm.formData.map(f => FormField(f.id, f.value))
      )
  }
}

case class APIFieldError(path: String, message: String)
object APIFieldError {
  implicit val formats = Json.format[APIFieldError]
}
case class APIError(code: String, message: String, fieldErrors: List[APIFieldError] = List.empty)
object APIError {
  implicit val formats = Json.format[APIError]
}

case class RequestValidationError(
  errors: Seq[(JsPath, Seq[JsonValidationError])],
  message: String = "Request body failed validation")
    extends ApplicationError(message)
