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

package consolidator.services

import cats.data.NonEmptyList
import common.JsonFormat
import play.api.libs.json.Format
import julienrf.json.derived

sealed trait SubmissionResult

case class FileUploadSubmissionResult(envelopeIds: NonEmptyList[String]) extends SubmissionResult
case class S3SubmissionResult(files: NonEmptyList[String]) extends SubmissionResult

object SubmissionResult extends JsonFormat {
  implicit val format: Format[SubmissionResult] = derived.oformat()
}
