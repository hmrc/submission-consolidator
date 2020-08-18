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

import java.time.{ Instant, ZoneId }
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import collector.repositories.{ DataGenerators, FormRepository }
import common.Time
import consolidator.repositories.{ ConsolidatorJobDataRepository, GenericConsolidatorJobDataError }
import org.mockito.ArgumentMatchersSugar
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

class ConsolidatorServiceSpec
    extends AnyWordSpec with Matchers with BeforeAndAfterAll with IdiomaticMockito with ArgumentMatchersSugar
    with DataGenerators with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(Span(10, Seconds), Span(1, Millis))
  implicit val actorSystem = ActorSystem("ConsolidatorServiceSpec")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")

  trait TestFixture {
    val mockFormRepository = mock[FormRepository](withSettings.lenient())
    val mockConsolidatorJobDataRepository = mock[ConsolidatorJobDataRepository](withSettings.lenient())
    lazy val _batchSize = 100
    lazy val _maxSizeInBytes: Long = 25 * 1024 * 1024
    lazy val _maxPerFileSizeInBytes: Long = 10 * 1024 * 1024
    lazy val _bufferInBytes: Long = 0
    lazy val consolidatorService =
      new ConsolidatorService(
        mockFormRepository,
        mockConsolidatorJobDataRepository,
        Configuration(
          "consolidator-job-config.batchSize" -> _batchSize,
        )
      ) {
        override lazy val maxSizeInBytes = _maxSizeInBytes
        override lazy val maxPerFileSizeInBytes = _maxPerFileSizeInBytes
        override lazy val bufferInBytes = _bufferInBytes
      }

    val projectId = "some-project-id"
    lazy val noOfForms = 1
    val now = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now
    lazy val forms = (1 to noOfForms)
      .map(
        seed =>
          genForm
            .pureApply(Gen.Parameters.default, Seed(seed))
            .copy(projectId = projectId))
      .toList
    val nowSuffix = DATE_TIME_FORMAT.format(now.atZone(ZoneId.systemDefault()))

    mockConsolidatorJobDataRepository.findRecentLastObjectId(*)(*) shouldReturn Future.successful(Right(None))
    mockFormRepository.formsSource(*, *, *, *) shouldReturn Source(forms).mapMaterializedValue(_ =>
      Future.successful(()))
  }

  "doConsolidation" when {
    "consolidation is successful" should {
      "no file if forms is empty" in new TestFixture {
        override lazy val noOfForms: Int = 0

        //when
        val future: Future[Option[ConsolidatorService.ConsolidationResult]] =
          consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult shouldBe None
          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(projectId, _batchSize, now.minusSeconds(5), None) wasCalled once
        }
      }

      "consolidate all form submissions into a single file, for the given project id" in new TestFixture {
        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult shouldNot be(empty)

          consolidationResult.get.lastObjectId shouldBe Some(forms.last.id)
          consolidationResult.get.count shouldBe noOfForms
          consolidationResult.get.outputPath.toString should endWith(s"/submission-consolidator/$projectId-$nowSuffix")

          val files = consolidationResult.get.outputPath.toFile.listFiles
          files.size shouldBe 1
          files.head.getName shouldBe "report-0.txt"
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList.head shouldEqual forms.head.toJsonLine()
          fileSource.close

          consolidationResult.get.lastObjectId shouldBe Some(forms.last.id)

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(projectId, _batchSize, now.minusSeconds(5), None) wasCalled once
        }
      }

      "consolidate form submissions into multiple files" in new TestFixture {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _maxSizeInBytes: Long = forms.map(_.toJsonLine().length).sum + 2 // +2 for new line characters
        override lazy val _maxPerFileSizeInBytes: Long = forms.head.toJsonLine().length + 1 // + 1 for newline character

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult shouldNot be(empty)
          consolidationResult.get.lastObjectId shouldBe Some(forms.last.id)
          consolidationResult.get.count shouldBe noOfForms
          val files = consolidationResult.get.outputPath.toFile.listFiles
          files.size shouldBe 2
          files.sorted.zipWithIndex.zip(forms).foreach {
            case ((file, index), form) =>
              file.getName shouldBe s"report-$index.txt"
              val fileSource = scala.io.Source.fromFile(file, "UTF-8")
              val lines = fileSource.getLines().toList
              lines.size shouldBe 1
              lines.head shouldEqual form.toJsonLine()
              fileSource.close
          }
        }
      }

      "skip forms when total files size exceeds limit" in new TestFixture {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _maxSizeInBytes: Long = forms.head.toJsonLine().length + 1 // +2 for new line characters
        override lazy val _maxPerFileSizeInBytes
          : Long = forms.head.toJsonLine().length + 1 // + 1 for newline characters

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult shouldNot be(empty)
          consolidationResult.get.lastObjectId shouldBe Some(forms.head.id)
          consolidationResult.get.count shouldBe 1

          val files = consolidationResult.get.outputPath.toFile.listFiles
          files.size shouldBe 1
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList.head shouldEqual forms.head.toJsonLine()
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
        }
      }

      "handle error when formsSource fails" in new TestFixture {
        mockFormRepository.formsSource(*, *, *, *) shouldReturn Source(forms)
          .map(_ => throw new RuntimeException("mongo db unavailable"))
          .mapMaterializedValue(_ => Future.successful(()))

        //when
        val future = consolidatorService.doConsolidation(projectId).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe a[RuntimeException]
          error.getMessage shouldBe "IO operation was stopped unexpectedly because of java.lang.RuntimeException: mongo db unavailable"
        }
      }
    }
  }
}
