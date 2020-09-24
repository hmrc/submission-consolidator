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

package consolidator.services.formatters

import java.time.Instant

import collector.repositories.{ DataGenerators, Form, FormField }
import collector.repositories.Form.DATE_TIME_FORMATTER
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.{ JsString, Writes, __ }
import play.api.libs.functional.syntax._

class JSONLineFormatterSpec extends AnyWordSpec with Matchers with DataGenerators with ScalaCheckDrivenPropertyChecks {

  private val instantJsonLineWrites: Writes[Instant] = (instant: Instant) =>
    JsString(DATE_TIME_FORMATTER.format(instant))
  private val formJsonLineWrites: Writes[Form] = (
    (__ \ "submissionRef").write[String] and
      (__ \ "projectId").write[String] and
      (__ \ "templateId").write[String] and
      (__ \ "customerId").write[String] and
      (__ \ "submissionTimestamp").write[Instant](instantJsonLineWrites) and
      (__ \ "formData").write[Seq[FormField]]
  )(f => (f.submissionRef, f.projectId, f.templateId, f.customerId, f.submissionTimestamp, f.formData))

  "formLine" should {
    "return the form as formatted line in jsonline format" in {
      forAll(genForm) { form =>
        val result = JSONLineFormatter.formLine(form)
        result shouldBe formJsonLineWrites.writes(form).toString
      }
    }
  }

  "ext" should {
    "return txt" in {
      JSONLineFormatter.ext shouldBe "txt"
    }
  }
}
