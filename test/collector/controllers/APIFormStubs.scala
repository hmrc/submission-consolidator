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

package collector.controllers

import play.api.libs.json.Json
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.libs.json.Reads._

object APIFormStubs {
  val validForm =
    Json.parse("""
                 | {
                 |   "submissionRef" : "ABC1-DEF2-HIJ3",
                 |   "projectId": "some-project-id",
                 |   "templateId": "some-template-id",
                 |   "customerId": "some-customer-id",
                 |   "submissionTimestamp": "2020-01-01T00:00:00Z",
                 |   "formData": [
                 |     {
                 |       "id": "1",
                 |       "value": "value1"
                 |     }
                 |   ]
                 | }
                 |""".stripMargin)

  val validFormMissingFormData = validForm
    .transform(
      (__ \ "formData").json.prune
    )
    .get

  val validFormEmptyFormData = validForm
    .transform(
      (__ \ "formData").json.update(
        of[JsArray].map(_ => JsArray.empty)
      )
    )
    .get

  val formEmptySubmissionRef =
    validForm
      .transform(
        (__ \ "submissionRef").json.update(
          of[JsString].map(_ => toJson(""))
        )
      )
      .get

  val formMissingSubmissionRef = validForm
    .transform(
      (__ \ "submissionRef").json.prune
    )
    .get

  val formEmptyProjectId =
    validForm
      .transform(
        (__ \ "projectId").json.update(
          of[JsString].map(_ => toJson(""))
        )
      )
      .get

  val formMissingProjectId = validForm
    .transform(
      (__ \ "projectId").json.prune
    )
    .get

  val formEmptyTemplateId =
    validForm
      .transform(
        (__ \ "templateId").json.update(
          of[JsString].map(_ => toJson(""))
        )
      )
      .get

  val formMissingTemplateId = validForm
    .transform(
      (__ \ "templateId").json.prune
    )
    .get

  val formEmptyCustomerId =
    validForm
      .transform(
        (__ \ "customerId").json.update(
          of[JsString].map(_ => toJson(""))
        )
      )
      .get

  val formMissingCustomerId = validForm
    .transform(
      (__ \ "customerId").json.prune
    )
    .get

  val formInvalidSubmissionTimestamp = validForm
    .transform(
      (__ \ "submissionTimestamp").json.update(
        of[JsString].map(_ => toJson("2020-01-01"))
      )
    )
    .get

  val formMissingSubmissionTimestamp = validForm
    .transform(
      (__ \ "submissionTimestamp").json.prune
    )
    .get

  val formMissingFormDataId = validForm
    .transform(
      (__ \ "formData").json.update(
        of[JsArray].map(_ => JsArray(Array(Json.obj("value" -> "value1"))))
      )
    )
    .get

  val formEmptyFormDataId = validForm
    .transform(
      (__ \ "formData").json.update(
        of[JsArray].map(_ => JsArray(Array(Json.obj("id" -> "", "value" -> "value1"))))
      )
    )
    .get

  val formMissingFormDataValue = validForm
    .transform(
      (__ \ "formData").json.update(
        of[JsArray].map(_ => JsArray(Array(Json.obj("id" -> "1"))))
      )
    )
    .get

  val formEmptyFormDataValue = validForm
    .transform(
      (__ \ "formData").json.update(
        of[JsArray].map(_ => JsArray(Array(Json.obj("id" -> "1", "value" -> ""))))
      )
    )
    .get
}
