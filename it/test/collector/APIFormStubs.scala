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

package collector

import play.api.libs.json.Json.toJson
import play.api.libs.json.{ JsString, Json, __ }
import play.api.libs.json.Reads.of

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
  val formEmptySubmissionRef =
    validForm
      .transform(
        (__ \ "submissionRef").json.update(
          of[JsString].map(_ => toJson(""))
        )
      )
      .get
}
