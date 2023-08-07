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

package common

import consolidator.repositories.{ CorrelationId, NotificationStatus }
import play.api.libs.json.{ JsError, JsString, JsSuccess, Reads }
import play.api.mvc.QueryStringBindable

object ValueClassBinder {

  implicit val notificationStatusBinder: QueryStringBindable[NotificationStatus] = valueClassQueryBinder(
    NotificationStatus.fromName
  )
  implicit val correlationIdsBinder: QueryStringBindable[CorrelationId] = valueClassQueryBinder(_.value)
  private def valueClassQueryBinder[A: Reads](
    fromAtoString: A => String
  )(implicit stringBinder: QueryStringBindable[String]) =
    new QueryStringBindable[A] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, A]] =
        stringBinder.bind(key, params).map(_.flatMap(parseString[A]))

      override def unbind(key: String, a: A): String =
        stringBinder.unbind(key, fromAtoString(a))
    }

  private def parseString[A: Reads](str: String) =
    JsString(str).validate[A] match {
      case JsSuccess(a, _) => Right(a)
      case JsError(error)  => Left("No valid value in url binding: " + str + ". Error: " + error)
    }

}
