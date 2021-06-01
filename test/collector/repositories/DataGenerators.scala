/*
 * Copyright 2021 HM Revenue & Customs
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

import cats.data.NonEmptyList
import java.time.Instant

import consolidator.repositories.ConsolidatorJobData
import consolidator.services.{ FileUploadSubmissionResult, S3SubmissionResult, SubmissionResult }
import org.scalacheck.Gen
import reactivemongo.bson.BSONObjectID

trait DataGenerators {

  val genInstant: Gen[Instant] = for {
    numSeconds <- Gen.choose(0, 10000)
  } yield Instant.now().minusSeconds(numSeconds.toLong)

  val genFormField: Gen[FormField] = for {
    id    <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    value <- Gen.alphaNumStr.suchThat(_.nonEmpty)
  } yield FormField(id, value)

  val genFileUploadSubmissionResult: Gen[FileUploadSubmissionResult] = for {
    envelopeIds <- Gen.listOf(Gen.uuid.toString).suchThat(_.nonEmpty)
  } yield FileUploadSubmissionResult(NonEmptyList.fromListUnsafe(envelopeIds))

  val s3SubmissionResult: Gen[S3SubmissionResult] = for {
    fileNames <- Gen.listOf(Gen.alphaNumStr.suchThat(_.nonEmpty))
  } yield S3SubmissionResult(NonEmptyList.fromListUnsafe(fileNames))

  val genSubmissionResult: Gen[SubmissionResult] =
    Gen.oneOf(genFileUploadSubmissionResult, s3SubmissionResult)

  val genForm = for {
    submissionRef       <- Gen.uuid.map(_.toString)
    projectId           <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    templateId          <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    customerId          <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    submissionTimestamp <- genInstant
    formData            <- Gen.listOf(genFormField)
  } yield
    Form(
      submissionRef,
      projectId,
      templateId,
      customerId,
      submissionTimestamp,
      formData
    )

  val genConsolidatorJobData = for {
    projectId        <- Gen.alphaNumStr.suchThat(_.nonEmpty)
    startTimestamp   <- genInstant
    endTimestamp     <- genInstant
    lastObjectId     <- Gen.some(BSONObjectID.generate())
    error            <- Gen.const(None)
    submissionResult <- Gen.option(genSubmissionResult)
  } yield
    ConsolidatorJobData(
      projectId,
      startTimestamp,
      endTimestamp,
      lastObjectId,
      error,
      submissionResult match {
        case Some(FileUploadSubmissionResult(envelopeIds)) => Some(envelopeIds.toList.mkString(","))
        case _                                             => Some("")
      },
      submissionResult
    )

  val genConsolidatorJobDataWithError = for {
    consolidatorData <- genConsolidatorJobData
    error            <- Gen.some(Gen.alphaNumStr.suchThat(_.nonEmpty))
  } yield consolidatorData.copy(lastObjectId = None, error = error)

}
