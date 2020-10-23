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

import collector.repositories.DataGenerators
import org.apache.commons.text.StringEscapeUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class CSVFormatterSpec extends AnyWordSpec with Matchers with DataGenerators with ScalaCheckDrivenPropertyChecks {

  "formLine" should {
    "return the form formatted as csv" in {
      forAll(genForm) { form =>
        val headers = form.formData.map(_.id).sorted
        val result = CSVFormatter(headers).formLine(form)
        result shouldBe headers
          .map(h => form.formData.find(_.id == h).map(f => StringEscapeUtils.escapeCsv(f.value)).getOrElse(""))
          .mkString(",")
      }
    }
  }

  "headerLine" should {
    "return headers formatted as csv" in {
      forAll(genForm) { form =>
        val headers = form.formData.map(_.id).sorted
        val result = CSVFormatter(headers).headerLine
        result shouldBe Some(headers.map(StringEscapeUtils.escapeCsv).mkString(","))
      }
    }
  }

  "ext" should {
    "return xls" in {
      CSVFormatter(List.empty).ext shouldBe "xls"
    }
  }
}
