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

import org.scalacheck.Gen

trait DataGenerators {

  val genInstant: Gen[Instant] = for {
    numSeconds <- Gen.choose(0, 10000)
  } yield Instant.now().minusSeconds(numSeconds)

  val genFormField: Gen[FormField] = for {
    id    <- Gen.alphaStr
    value <- Gen.alphaStr
  } yield FormField(id, value)

  val genForm = for {
    submissionRef       <- Gen.uuid.map(_.toString)
    formId              <- Gen.alphaNumStr
    templateId          <- Gen.alphaNumStr
    customerId          <- Gen.alphaNumStr
    submissionTimestamp <- genInstant
    formData            <- Gen.listOf(genFormField)
  } yield
    Form(
      submissionRef,
      formId,
      templateId,
      customerId,
      submissionTimestamp,
      formData
    )
}
