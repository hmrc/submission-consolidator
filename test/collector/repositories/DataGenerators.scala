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

import consolidator.repositories.ConsolidatorJobData
import org.bson.types.ObjectId
import org.scalacheck.Gen

import java.time.Instant

trait DataGenerators {

  val genInstant: Gen[Instant] = for {
    numSeconds <- Gen.choose(0, 10000)
  } yield Instant.now().minusSeconds(numSeconds.toLong)

  val genFormField: Gen[FormField] = for {
    id    <- Gen.alphaNumStr.suchThat(!_.isEmpty)
    value <- Gen.alphaNumStr.suchThat(!_.isEmpty)
  } yield FormField(id, value)

  val genForm = for {
    submissionRef       <- Gen.uuid.map(_.toString)
    projectId           <- Gen.alphaNumStr.suchThat(!_.isEmpty)
    templateId          <- Gen.alphaNumStr.suchThat(!_.isEmpty)
    customerId          <- Gen.alphaNumStr.suchThat(!_.isEmpty)
    submissionTimestamp <- genInstant
    formData            <- Gen.listOf(genFormField)
  } yield Form(
    submissionRef,
    projectId,
    templateId,
    customerId,
    submissionTimestamp,
    formData
  )

  val genConsolidatorJobData = for {
    projectId      <- Gen.alphaNumStr.suchThat(!_.isEmpty)
    startTimestamp <- genInstant
    endTimestamp   <- genInstant
    lastObjectId   <- Gen.some(ObjectId.get())
    error          <- Gen.const(None)
    envelopeId     <- Gen.uuid.map(u => Some(u.toString))
  } yield ConsolidatorJobData(projectId, startTimestamp, endTimestamp, lastObjectId, error, envelopeId, ObjectId.get())

  val genConsolidatorJobDataWithError = for {
    consolidatorData <- genConsolidatorJobData
    error            <- Gen.some(Gen.alphaNumStr.suchThat(!_.isEmpty))
  } yield consolidatorData.copy(lastObjectId = None, error = error)

}
