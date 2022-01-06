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

package common

import cats.data.NonEmptyList
import play.api.libs.json.{ Format, JsError, JsResult, JsString, JsSuccess, JsValue, Json }

import java.net.URI
import scala.util.{ Failure, Success, Try }

trait JsonFormat {
  implicit def nelFormat[T: Format]: Format[NonEmptyList[T]] =
    new Format[NonEmptyList[T]] {
      override def writes(o: NonEmptyList[T]): JsValue = Json.toJson(o.toList)

      override def reads(json: JsValue): JsResult[NonEmptyList[T]] =
        NonEmptyList.fromList(json.as[List[T]]) match {
          case None      => JsError("Cannot create NonEmptyList from empty list")
          case Some(nel) => JsSuccess(nel)
        }
    }

  implicit val uriFormat: Format[URI] =
    new Format[URI] {
      override def writes(u: URI): JsValue = JsString(u.toString)

      override def reads(json: JsValue): JsResult[URI] =
        json match {
          case JsString(value) =>
            Try(new URI(value)) match {
              case Success(value)     => JsSuccess(value)
              case Failure(exception) => JsError(s"URI is invalid $value [error=$exception]")
            }
          case other => JsError(s"json must be a JsString $other")
        }
    }
}

object JsonFormat extends JsonFormat
