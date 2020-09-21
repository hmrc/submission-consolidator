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

import java.nio.file.{ Files, Path, Paths }
import java.time.{ Instant, ZoneId }
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import collector.repositories.{ DataGenerators, FormRepository }
import common.Time
import consolidator.repositories.{ ConsolidatorJobDataRepository, GenericConsolidatorJobDataError }
import consolidator.services.formatters.{ CSVFormatter, ConsolidationFormat, JSONLineFormatter }
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
    lazy val _reportPerFileSizeInBytes: Long = 4 * 1024 * 1024
    lazy val reportDir: Path = Files.createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/ConsolidatorServiceSpec-${System.currentTimeMillis()}")
    )

    lazy val consolidatorService =
      new ConsolidatorService(
        mockFormRepository,
        mockConsolidatorJobDataRepository,
        Configuration(
          "consolidator-job-config.batchSize" -> _batchSize,
        )
      ) {
        override lazy val reportPerFileSizeInBytes: Long = _reportPerFileSizeInBytes
      }

    val projectId = "some-project-id"
    lazy val format = ConsolidationFormat.jsonl
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

  trait TestFixtureCSVFormat extends TestFixture {
    override lazy val format = ConsolidationFormat.csv
    val headers: List[String] = forms.flatMap(_.formData).map(_.id).sorted
    mockFormRepository.distinctFormDataIds(*, *)(*) shouldReturn Future.successful(Right(headers))
  }

  "doConsolidation" when {
    "consolidation is successful" should {
      "no file if forms is empty" in new TestFixture {
        override lazy val noOfForms: Int = 0

        //when
        val future: Future[ConsolidatorService.ConsolidationResult] =
          consolidatorService.doConsolidation(projectId, reportDir, format).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.lastObjectId shouldBe None
          consolidationResult.count shouldBe 0
          consolidationResult.outputPath.toFile.listFiles().length shouldBe 1
          consolidationResult.outputPath.toFile.listFiles().head.length shouldBe 0

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(projectId, _batchSize, now.minusSeconds(5), None) wasCalled once
        }
      }

      "consolidate all form submissions into a single file, for the given project id" in new TestFixture {
        //when
        val future = consolidatorService.doConsolidation(projectId, reportDir, format).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.lastObjectId shouldBe Some(forms.last.id)
          consolidationResult.count shouldBe noOfForms
          consolidationResult.outputPath shouldBe reportDir

          val files = consolidationResult.outputPath.toFile.listFiles
          files.size shouldBe 1
          files.head.getName shouldBe "report-0.txt"
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList.head shouldEqual JSONLineFormatter.format(forms.head)
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(projectId, _batchSize, now.minusSeconds(5), None) wasCalled once
        }
      }

      "consolidate all form submissions into a single file, for the given project id and csv format" in new TestFixtureCSVFormat {
        //when
        val future = consolidatorService.doConsolidation(projectId, reportDir, format).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          val files = consolidationResult.outputPath.toFile.listFiles
          files.size shouldBe 1
          files.head.getName shouldBe "report-0.txt"
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList.head shouldEqual CSVFormatter(headers).format(forms.head)
          fileSource.close
        }
      }

      "consolidate form submissions into multiple files" in new TestFixture {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _reportPerFileSizeInBytes
          : Long = JSONLineFormatter.format(forms.head).length + 1 // + 1 for newline character

        //when
        val future = consolidatorService.doConsolidation(projectId, reportDir, format).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.lastObjectId shouldBe Some(forms.last.id)
          consolidationResult.count shouldBe noOfForms
          val files = consolidationResult.outputPath.toFile.listFiles
          files.size shouldBe 2
          files.sorted.zipWithIndex.zip(forms).foreach {
            case ((file, index), form) =>
              file.getName shouldBe s"report-$index.txt"
              val fileSource = scala.io.Source.fromFile(file, "UTF-8")
              val lines = fileSource.getLines().toList
              lines.size shouldBe 1
              lines.head shouldEqual JSONLineFormatter.format(form)
              fileSource.close
          }
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
        val future = consolidatorService.doConsolidation(projectId, reportDir, format).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe GenericConsolidatorJobDataError("some error")
        }
      }

      "handle error when formsSource fails" in new TestFixture {
        mockFormRepository.formsSource(*, *, *, *) shouldReturn Source(forms)
          .map(_ => throw new RuntimeException("mongo db unavailable"))
          .mapMaterializedValue(_ => Future.successful(()))

        //when
        val future = consolidatorService.doConsolidation(projectId, reportDir, format).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe a[RuntimeException]
          error.getMessage shouldBe "IO operation was stopped unexpectedly because of java.lang.RuntimeException: mongo db unavailable"
        }
      }
    }
  }
}
