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

package consolidator

import cats.effect.IO

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

trait IOUtils {
  def liftIO[T <: Throwable, A](fa: => Future[Either[T, A]])(implicit ec: ExecutionContext): IO[A] =
    IO.async { cb =>
      // This triggers evaluation of the by-name param and of onComplete,
      // so it's OK to have side effects in this callback
      fa.onComplete {
        case Success(a) =>
          a match {
            case Left(e)  => cb(Left(e))
            case Right(v) => cb(Right(v))
          }
        case Failure(e) => cb(Left(e))
      }
    }
}
