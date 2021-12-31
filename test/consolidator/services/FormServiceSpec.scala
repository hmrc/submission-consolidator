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

import collector.repositories.{ DataGenerators, FormRepository }
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{ LocalDate, ZoneOffset }
import scala.concurrent.ExecutionContext.Implicits.global

class FormServiceSpec
    extends AnyWordSpec with Matchers with BeforeAndAfterAll with IdiomaticMockito with ArgumentMatchersSugar
    with DataGenerators with ScalaFutures {

  trait TestFixture {
    val mockFormRepository: FormRepository = mock[FormRepository](withSettings.lenient())
    val formService: FormService = new FormService(mockFormRepository)
  }

  "removeByPeriod" should {
    "remove all forms until the given period" in new TestFixture {
      val period: Long = 6
      val untilInstant = LocalDate.now().minusMonths(period).atStartOfDay.atZone(ZoneOffset.UTC).toInstant

      formService.removeByPeriod(period)
      mockFormRepository.remove(untilInstant)(*) wasCalled once
    }
  }
}
