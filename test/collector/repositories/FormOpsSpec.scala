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

import java.time.ZoneId
import java.time.format.DateTimeFormatter

import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FormOpsSpec extends AnyFunSuite with DataGenerators with Matchers {
  val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))

  test("toJsonLine converts the form to a JSON line") {
    val someForm = genForm.pureApply(Gen.Parameters.default, Seed(1))

    val expectedJsonLine =
      s"""
         | {
         |  "submissionRef":"${someForm.submissionRef}",
         |  "projectId":"${someForm.projectId}",
         |  "templateId":"${someForm.templateId}",
         |  "customerId":"${someForm.customerId}",
         |  "submissionTimestamp":"${DATE_TIME_FORMATTER.format(someForm.submissionTimestamp)}",
         |  "formData":[
         |   ${someForm.formData
           .map(fData => s"""
                            |   {
                            |     "id":"${fData.id}",
                            |     "value":"${fData.value}"
                            |   }
                            |""".stripMargin)
           .mkString(",")}
         |    ]
         | }
         |""".stripMargin.split("\n").map(_.trim).mkString("")

    someForm.toJsonLine() shouldBe expectedJsonLine
  }
}
