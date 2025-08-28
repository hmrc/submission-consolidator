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

import common.UniqueReferenceGenerator.{ UniqueRef, UniqueReferenceGenError }
import common.repositories.UniqueIdRepository
import common.repositories.UniqueIdRepository.UniqueId
import javax.inject.{ Inject, Singleton }
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NoStackTrace

@Singleton
class UniqueReferenceGenerator @Inject() (uniqueIdRepository: UniqueIdRepository)(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def generate(length: Int): Future[Either[UniqueReferenceGenError, UniqueRef]] =
    uniqueIdRepository
      .insertWithRetries { () =>
        val uniqueRef = RandomStringUtils.secure().nextAlphanumeric(length)
        logger.debug("Validating unique reference " + uniqueRef)
        UniqueId(uniqueRef.toUpperCase)
      }
      .map {
        case Some(uid) => Right(UniqueRef(uid.value))
        case None      => Left(UniqueReferenceGenError("Failed to generate unique id"))
      }
}

object UniqueReferenceGenerator {
  case class UniqueRef(ref: String) extends AnyVal

  case class UniqueReferenceGenError(message: String) extends NoStackTrace
}
