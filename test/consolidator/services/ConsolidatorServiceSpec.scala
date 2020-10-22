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
import java.time.temporal.ChronoUnit
import java.time.{ Instant, ZoneId }

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import collector.repositories.{ DataGenerators, Form, FormRepository }
import common.Time
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository, GenericConsolidatorJobDataError }
import consolidator.scheduler.UntilTime
import consolidator.services.formatters.ConsolidationFormat.ConsolidationFormat
import consolidator.services.formatters.{ CSVFormatter, ConsolidationFormat, FormFormatter, FormFormatterFactory, JSONLineFormatter }
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
import reactivemongo.bson.BSONObjectID

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConsolidatorServiceSpec
    extends AnyWordSpec with Matchers with BeforeAndAfterAll with IdiomaticMockito with ArgumentMatchersSugar
    with DataGenerators with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(Span(10, Seconds), Span(1, Millis))
  implicit val actorSystem: ActorSystem = ActorSystem("ConsolidatorServiceSpec")

  trait TestFixture {
    val mockFormRepository: FormRepository = mock[FormRepository](withSettings.lenient())
    val mockConsolidatorJobDataRepository: ConsolidatorJobDataRepository =
      mock[ConsolidatorJobDataRepository](withSettings.lenient())
    lazy val _batchSize = 100
    lazy val _reportPerFileSizeInBytes: Long = 4 * 1024 * 1024
    lazy val reportDir: Path = Files.createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/ConsolidatorServiceSpec-${System.currentTimeMillis()}")
    )

    lazy val consolidatorService: ConsolidatorService =
      new ConsolidatorService(
        mockFormRepository,
        mockConsolidatorJobDataRepository,
        new FormFormatterFactory(mockFormRepository),
        Configuration(
          "consolidator-job-config.batchSize" -> _batchSize,
        )
      ) {
        override lazy val reportPerFileSizeInBytes: Long = _reportPerFileSizeInBytes
      }

    lazy val projectId = "some-project-id"
    lazy val format: ConsolidationFormat = ConsolidationFormat.jsonl
    lazy val formatter: FormFormatter = JSONLineFormatter
    val classificationType = "some-classification"
    val businessArea = "some-business-area"
    val formConsolidatorParams: ScheduledFormConsolidatorParams =
      ScheduledFormConsolidatorParams(projectId, classificationType, businessArea, format, UntilTime.now)
    lazy val noOfForms = 1
    val now: Instant = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now
    lazy val forms = (1 to noOfForms)
      .map(
        seed =>
          genForm
            .pureApply(Gen.Parameters.default, Seed(seed))
            .copy(projectId = projectId))
      .toList

    mockConsolidatorJobDataRepository.findRecentLastObjectId(*)(*) shouldReturn Future.successful(Right(None))
    mockFormRepository.formsSource(*, *, *, *) shouldReturn Source(forms).mapMaterializedValue(_ =>
      Future.successful(()))

    def maxReportFileSize(forms: List[Form]) =
      forms.map { form =>
        formatter.headerLine.map(_.length + 1).getOrElse(0) + formatter.formLine(form).length + 1
      }.max
  }

  trait TestFixtureCSVFormat extends TestFixture {
    def formDataHeaders(form: Form): List[String] =
      form.formData.map(_.id).sorted

    val headers: List[String] = forms.flatMap(formDataHeaders).sorted
    override lazy val format: ConsolidationFormat = ConsolidationFormat.csv
    override lazy val formatter: FormFormatter = CSVFormatter(headers)
    mockFormRepository.distinctFormDataIds(*, *)(*) shouldReturn Future.successful(Right(headers))
  }

  "doConsolidation - scheduler form params" when {
    "consolidation is successful" should {

      "not generate consolidation files if forms is empty" in new TestFixture {
        override lazy val noOfForms: Int = 0

        //when
        val future =
          consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult shouldBe None

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            None,
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant) wasCalled once
        }
      }

      "consolidate all form submissions into a single consolidation file" in new TestFixture {
        //when

        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          val files = consolidationResult.get.reportFiles
          files.map(_.getName) shouldBe Array("report-0.xls")
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList shouldEqual List(formatter.formLine(forms.head))
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            None,
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant) wasCalled once
        }
      }

      "consolidate form submissions into multiple files" in new TestFixture {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _reportPerFileSizeInBytes: Long = maxReportFileSize(forms)

        //when
        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms
          val files = consolidationResult.get.reportFiles
          files.size shouldBe 2
          files.sorted.zipWithIndex.zip(forms).foreach {
            case ((file, index), form) =>
              file.getName shouldBe s"report-$index.xls"
              val fileSource = scala.io.Source.fromFile(file, "UTF-8")
              val lines = fileSource.getLines().toList
              lines.size shouldBe 1
              lines.head shouldEqual formatter.formLine(form)
              fileSource.close
          }
        }
      }

      "consolidate form submissions, starting with ObjectId from previous run" in new TestFixture {

        val consolidatorJobData = ConsolidatorJobData(
          projectId,
          now,
          now,
          Some(BSONObjectID.generate()),
          None,
          Some("previous-envelope-id")
        )
        mockConsolidatorJobDataRepository.findRecentLastObjectId(*)(*) shouldReturn Future.successful(
          Right(Some(consolidatorJobData)))

        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          val files = consolidationResult.get.reportFiles
          files.map(_.getName) shouldBe Array("report-0.xls")
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList shouldEqual List(formatter.formLine(forms.head))
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            consolidatorJobData.lastObjectId,
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant) wasCalled once
        }
      }

      "consolidate form submissions, with user form consolidator params" in new TestFixture {
        val startInstant = now.minus(2, ChronoUnit.DAYS)
        val endInstant = now.minus(1, ChronoUnit.DAYS)
        val userFormConsolidatorParams =
          ManualFormConsolidatorParams(projectId, classificationType, businessArea, format, startInstant, endInstant)
        val future = consolidatorService.doConsolidation(reportDir, userFormConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          val files = consolidationResult.get.reportFiles
          files.map(_.getName) shouldBe Array("report-0.xls")
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList shouldEqual List(formatter.formLine(forms.head))
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasNever called

          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            Some(BSONObjectID.fromTime(startInstant.toEpochMilli)),
            endInstant) wasCalled once
        }
      }

      "consolidate form submissions into multiple files (csv format)" in new TestFixtureCSVFormat {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _reportPerFileSizeInBytes: Long = maxReportFileSize(forms)

        //when
        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            None,
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant) wasCalled once

          val files = consolidationResult.get.reportFiles
          files.size shouldBe 2
          files.sorted.zipWithIndex.zip(forms).foreach {
            case ((file, index), form) =>
              file.getName shouldBe s"report-$index.csv"
              val fileSource = scala.io.Source.fromFile(file, "UTF-8")
              val lines = fileSource.getLines().toList
              lines shouldEqual List(formatter.headerLine.get, formatter.formLine(form))
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
        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe GenericConsolidatorJobDataError("some error")
        }
      }

      "handle error when formsSource fails" in new TestFixture {
        mockFormRepository.formsSource(*, *, *, *) shouldReturn Source(forms)
          .map(_ => throw new RuntimeException("mongo db unavailable"))
          .mapMaterializedValue(_ => Future.successful(()))

        //when
        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future.failed) { error =>
          error shouldBe a[RuntimeException]
          error.getMessage shouldBe "IO operation was stopped unexpectedly because of java.lang.RuntimeException: mongo db unavailable"
        }
      }
    }
  }
}
