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
import org.mockito.ArgumentMatchersSugar
import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UniqueReferenceGeneratorSpec
    extends AnyWordSpec with Matchers with IdiomaticMockito with ArgumentMatchersSugar with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(1, Millis))

  trait TestFixture {
    val uniqueIdRepository = mock[UniqueIdRepository]
    lazy val uniqueIdLength = 1
    lazy val uniqueId: Option[UniqueId] = Some(UniqueId("A" * uniqueIdLength))
    uniqueIdRepository.insertWithRetries(*) shouldReturn Future.successful(uniqueId)
    val uniqueReferenceGenerator = new UniqueReferenceGenerator(uniqueIdRepository)
  }

  trait TestErrorFixture extends TestFixture {
    override lazy val uniqueId = None
  }

  "generate" when {
    "unique reference is generated successfully" should {
      "return the generated reference" in new TestFixture {

        val future = uniqueReferenceGenerator.generate(uniqueIdLength)

        whenReady(future) { result =>
          result shouldBe Right(UniqueRef("A" * uniqueIdLength))
          val captor = ArgCaptor[() => UniqueId]
          uniqueIdRepository.insertWithRetries(captor) wasCalled once
          captor.value().value.length shouldBe uniqueIdLength
        }
      }
    }

    "unique reference generation failed" should {
      "return an error" in new TestErrorFixture {
        val future = uniqueReferenceGenerator.generate(uniqueIdLength)

        whenReady(future) { result =>
          result shouldBe Left(UniqueReferenceGenError("Failed to generate unique id"))
        }
      }
    }
  }
}
