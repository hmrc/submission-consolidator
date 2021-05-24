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

package collector.controllers

import play.api.libs.json.Json.toJson
import play.api.libs.json.{ JsPath, JsonValidationError }
import play.api.mvc.Result
import play.api.mvc.Results._
import collector.common.ApplicationError
import collector.controllers.ErrorCode._
import collector.repositories.{ DuplicateSubmissionRef, MongoGenericError, MongoUnavailable }

trait ErrorHandler {
  def handleError(applicationError: ApplicationError): Result =
    applicationError match {
      case RequestValidationError(errors, message) =>
        BadRequest(
          toJson(
            APIError(
              REQUEST_VALIDATION_FAILED,
              message,
              mapToAPIFieldErrors(errors)
            )
          )
        )
      case DuplicateSubmissionRef(submissionRef, message) =>
        Conflict(toJson(APIError(DUPLICATE_SUBMISSION_REFERENCE, s"$message [$submissionRef]")))
      case MongoUnavailable(message) =>
        ServiceUnavailable(toJson(APIError(SERVICE_UNAVAILABLE, message)))
      case MongoGenericError(message) =>
        InternalServerError(toJson(APIError(INTERNAL_ERROR, message)))
      case ManualConsolidationError(code, message) =>
        InternalServerError(
          toJson(
            APIError(code, message)
          )
        )
    }

  private def mapToAPIFieldErrors(errors: Seq[(JsPath, Seq[JsonValidationError])]) =
    errors.map {
      case (path, errors) =>
        APIFieldError(
          path.toString,
          errors
            .map { jsonError =>
              jsonError.messages.head match {
                case "error.minLength" =>
                  s"Minimum length should be ${jsonError.args.headOption.getOrElse("")}"
                case "error.path.missing" =>
                  s"Is required"
                case other => s"$other ${jsonError.args.headOption.getOrElse("")}".trim
              }
            }
            .mkString(",")
        )
    }.toList
}
