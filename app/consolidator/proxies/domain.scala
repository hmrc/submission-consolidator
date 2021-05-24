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

package consolidator.proxies

import collector.common.ApplicationError
import play.api.libs.json.Json

case class Metadata(application: String)
object Metadata {
  implicit val formats = Json.format[Metadata]
}
case class Constraints(
  maxItems: Int,
  maxSize: String,
  maxSizePerItem: String,
  contentTypes: List[String],
  allowZeroLengthFiles: Boolean)
object Constraints {
  implicit val formats = Json.format[Constraints]
}
case class CreateEnvelopeRequest(metadata: Metadata, constraints: Constraints)

object CreateEnvelopeRequest {
  implicit val formats = Json.format[CreateEnvelopeRequest]
}

case class RouteEnvelopeRequest(envelopeId: String, application: String, destination: String)
object RouteEnvelopeRequest {
  implicit val formats = Json.format[RouteEnvelopeRequest]
}

abstract class FileUploadError(message: String) extends ApplicationError(message)
object LocationHeaderMissingOrInvalid extends FileUploadError("Location header is missing or invalid")
case class GenericFileUploadError(message: String) extends FileUploadError(message)

case class FileId(value: String)
