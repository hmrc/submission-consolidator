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

import collector.repositories.FormRepository
import org.slf4j.{ Logger, LoggerFactory }

import java.time.{ LocalDate, ZoneOffset }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FormService @Inject()(
  formRepository: FormRepository
)(implicit ec: ExecutionContext) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def removeByPeriod(period: Long): Future[Unit] = {
    val date = LocalDate.now().minusMonths(period)
    val untilInstant = date.atStartOfDay.atZone(ZoneOffset.UTC).toInstant
    logger.info(s"Removing all forms until $untilInstant")

    formRepository.remove(untilInstant)
  }
}
