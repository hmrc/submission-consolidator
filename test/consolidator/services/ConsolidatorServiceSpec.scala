/*
 * Copyright 2020 HM Revenue & Customs
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

import collector.repositories.{ DataGenerators, FormRepository, MongoGenericError }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository, FormsMetadata, GenericConsolidatorJobDataError }
import org.mockito.ArgumentMatchersSugar
import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.IdiomaticMockito
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source

class ConsolidatorServiceSpec
    extends AnyWordSpec with Matchers with BeforeAndAfterAll with IdiomaticMockito with ArgumentMatchersSugar
    with DataGenerators with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(Span(10, Seconds), Span(1, Millis))

  trait TestFixture {
    val mockFormRepository = mock[FormRepository](withSettings.lenient())
    val mockConsolidatorJobDataRepository = mock[ConsolidatorJobDataRepository](withSettings.lenient())
    lazy val batchSize = 100
    lazy val consolidatorService =
      new ConsolidatorService(
        mockFormRepository,
        mockConsolidatorJobDataRepository,
        Configuration("consolidator-job-config.batchSize" -> batchSize)
      )

    val projectId = "some-project-id"
    lazy val noOfForms = 1
    lazy val forms = (1 to noOfForms)
      .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
      .toList

    mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) shouldReturn Future.successful(Right(None))
    mockFormRepository.getFormsMetadata(projectId)(*) shouldReturn Future.successful(
      Right(if (forms.isEmpty) None else Some(FormsMetadata(forms.size, forms.last.id)))
    )
    mockFormRepository.getForms(projectId, *)(*, *)(*) returns
      Future.successful(Right(forms))
    mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
  }

  "doConsolidation" when {
    "consolidation is successful" should {
      "no file if forms is empty" in new TestFixture {
        override lazy val noOfForms: Int = 0

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { file =>
          file.isEmpty shouldBe true

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.getFormsMetadata(projectId)(*) wasCalled once
          mockFormRepository.getForms(projectId, batchSize)(*, *)(
            *
          ) wasNever called
          val consolidatorJobDataCaptor = ArgCaptor[ConsolidatorJobData]
          mockConsolidatorJobDataRepository.add(consolidatorJobDataCaptor)(*) wasCalled once
          consolidatorJobDataCaptor.value.projectId shouldBe projectId
          consolidatorJobDataCaptor.value.lastObjectId shouldBe empty
          consolidatorJobDataCaptor.value.error shouldBe empty
          consolidatorJobDataCaptor.value.endTimestamp.isAfter(
            consolidatorJobDataCaptor.value.startTimestamp
          ) shouldBe true
        }
      }

      "consolidate all form submissions into a file, for the given project id" in new TestFixture {
        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { file =>
          val fileSource = Source.fromFile(file.get, "UTF-8")
          fileSource.getLines().toList.head shouldEqual forms.head.toJsonLine()
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.getFormsMetadata(projectId)(*) wasCalled once
          mockFormRepository.getForms(projectId, batchSize)(*, *)(
            *
          ) wasCalled once

          val consolidatorJobDataCaptor = ArgCaptor[ConsolidatorJobData]
          mockConsolidatorJobDataRepository.add(consolidatorJobDataCaptor)(*) wasCalled once
          consolidatorJobDataCaptor.value.projectId shouldBe projectId
          consolidatorJobDataCaptor.value.lastObjectId shouldBe Some(forms.last.id)
          consolidatorJobDataCaptor.value.endTimestamp.isAfter(
            consolidatorJobDataCaptor.value.startTimestamp
          ) shouldBe true
          consolidatorJobDataCaptor.value.error shouldBe empty
        }
      }

      "consolidate form submissions, in multiple batches" in new TestFixture {
        //given
        override lazy val batchSize = 1
        override lazy val noOfForms: Int = 2
        mockFormRepository.getForms(projectId, *)(*, *)(*) returns
          Future.successful(Right(forms.take(batchSize))) andThen Future.successful(
          Right(forms.drop(batchSize))
        )

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { file =>
          val fileSource = Source.fromFile(file.get, "UTF-8")
          val fileLines = fileSource.getLines().toList
          fileLines shouldEqual forms.map(_.toJsonLine())
          fileSource.close
        }
      }
    }

    "consolidation fails" should {
      "handle error when findRecentLastObjectId fails" in new TestFixture {
        //given
        mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) shouldReturn Future.successful(
          Left(GenericConsolidatorJobDataError("some error"))
        )

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe GenericConsolidatorJobDataError("some error")
          val consolidatorJobDataCaptor = ArgCaptor[ConsolidatorJobData]
          mockConsolidatorJobDataRepository.add(consolidatorJobDataCaptor)(*) wasCalled once
          consolidatorJobDataCaptor.value.projectId shouldBe projectId
          consolidatorJobDataCaptor.value.lastObjectId shouldBe None
          consolidatorJobDataCaptor.value.endTimestamp.isAfter(
            consolidatorJobDataCaptor.value.startTimestamp
          ) shouldBe true
          consolidatorJobDataCaptor.value.error shouldBe Some("some error")
        }
      }

      "handle error when getFormsMetadata fails" in new TestFixture {
        //given
        mockFormRepository.getFormsMetadata(projectId)(*) shouldReturn Future.successful(
          Left(MongoGenericError("some mongo error"))
        )

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe MongoGenericError("some mongo error")
        }
      }

      "handle error when getForms fails" in new TestFixture {
        override lazy val batchSize = 1
        override lazy val noOfForms: Int = 2
        mockFormRepository.getForms(projectId, *)(*, *)(*) returns
          Future.successful(
            Right(forms.take(batchSize))
          ) andThen Future.failed(
          new RuntimeException("mongo db unavailable")
        )

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe a[RuntimeException]
          error.getMessage shouldBe "mongo db unavailable"
        }
      }

      "handle error when ConsolidatorJobDataRepository add fails" in new TestFixture {
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(
          Left(GenericConsolidatorJobDataError("some error"))
        )

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe GenericConsolidatorJobDataError("some error")
        }
      }
    }
  }
}
