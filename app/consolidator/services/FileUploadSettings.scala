/*
 * Copyright 2022 HM Revenue & Customs
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

trait FileUploadSettings {

  val BYTES_IN_1_KB: Long = 1024
  val BYTES_IN_1_MB: Long = BYTES_IN_1_KB * BYTES_IN_1_KB

  lazy val maxSizeBytes: Long = 25 * BYTES_IN_1_MB
  lazy val maxPerFileBytes: Long = 10 * BYTES_IN_1_MB

  lazy val reportPerFileSizeInBytes: Long = 4 * BYTES_IN_1_MB
  lazy val maxReportAttachmentsSize
    : Long = maxSizeBytes - (200 * BYTES_IN_1_KB) // leaving 200KB for metadata.xml and iform.pdf
}
