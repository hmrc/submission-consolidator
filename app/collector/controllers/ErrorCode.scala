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

object ErrorCode {
  val REQUEST_VALIDATION_FAILED = "REQUEST_VALIDATION_FAILED"
  val DUPLICATE_SUBMISSION_REFERENCE = "DUPLICATE_SUBMISSION_REFERENCE"
  val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
  val INTERNAL_ERROR = "INTERNAL_ERROR"
  val MANUAL_CONSOLIDATION_FAILED = "MANUAL_CONSOLIDATION_FAILED"
  val INVALID_CONSOLIDATOR_JOB_ID = "INVALID_CONSOLIDATOR_JOB_ID"
  val CONSOLIDATOR_JOB_ALREADY_IN_PROGRESS = "CONSOLIDATOR_JOB_ALREADY_IN_PROGRESS"
}
