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

import java.nio.file.{ Files, Path, Paths }
import java.time.temporal.ChronoUnit
import java.time.{ Instant, ZoneId }
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import collector.repositories.{ DataGenerators, Form, FormField, FormRepository }
import common.Time
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository, GenericConsolidatorJobDataError }
import consolidator.scheduler.{ FileUpload, UntilTime }
import consolidator.TestHelper.excelFileRows
import ConsolidationFormat.ConsolidationFormat
import consolidator.services.sink.{ FormCSVFilePartWriter, FormJsonLineFilePartWriter }
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
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

  override implicit val patienceConfig = PatienceConfig(Span(15, Seconds), Span(1, Millis))
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
        Configuration(
          "consolidator-job-config.batchSize" -> _batchSize
        )
      ) {
        override lazy val reportPerFileSizeInBytes: Long = _reportPerFileSizeInBytes
      }

    lazy val projectId = "some-project-id"
    lazy val format: ConsolidationFormat = ConsolidationFormat.jsonl
    val classificationType = "some-classification"
    val businessArea = "some-business-area"
    val formConsolidatorParams: ScheduledFormConsolidatorParams =
      ScheduledFormConsolidatorParams(projectId, format, FileUpload(classificationType, businessArea), UntilTime.now)
    lazy val noOfForms = 1
    val now: Instant = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now
    lazy val forms: List[Form] = (1 to noOfForms)
      .map(
        i =>
          Form(
            s"some-sub-ref$i",
            "some-project",
            "some-template",
            s"some-customer$i",
            Instant.now(),
            (1 to 10).map(j => FormField(s"id$j", s"value$i$j")).toList
        ))
      .toList

    mockConsolidatorJobDataRepository.findRecentLastObjectId(*)(*) shouldReturn Future.successful(Right(None))
    mockFormRepository.formsSource(*, *, *, *) shouldReturn Source(forms).mapMaterializedValue(_ =>
      Future.successful(()))
  }

  trait TestFixtureCSVFormat extends TestFixture {

    override lazy val format: ConsolidationFormat = ConsolidationFormat.csv

    lazy val headers: List[String] = forms.flatMap(_.formData.map(_.id).sorted).distinct.sorted

    mockFormRepository.distinctFormDataIds(*, *)(*) shouldReturn Future.successful(Right(headers))

    lazy val maxReportFileSize: Long =
      forms
        .map { form =>
          FormCSVFilePartWriter.toCSV(headers).length + 1 + FormCSVFilePartWriter.toCSV(form, headers).length + 1
        }
        .max
        .toLong

  }

  trait TestFixtureXLSXFormat extends TestFixture {

    override lazy val format: ConsolidationFormat = ConsolidationFormat.xlsx

    lazy val headers: List[String] = forms.flatMap(_.formData.map(_.id).sorted).distinct.sorted

    mockFormRepository.distinctFormDataIds(*, *)(*) shouldReturn Future.successful(Right(headers))

    lazy val maxReportFileSize: Long =
      forms
        .map { form =>
          val headersLength = headers
            .mkString("")
            .getBytes("UTF-8")
            .length
          val formsLength =
            headers.flatMap(h => form.formData.find(_.id == h).map(_.value)).mkString("").getBytes("UTF-8").length
          headersLength + formsLength
        }
        .max
        .toLong

  }

  trait TestFixtureJSONLineFormat extends TestFixture {

    lazy val maxReportFileSize: Long =
      forms
        .map { form =>
          FormJsonLineFilePartWriter.toJson(form).length + 1
        }
        .max
        .toLong
  }

  "doConsolidation - scheduler form params" when {
    "consolidation is successful" should {

      "not generate consolidation files if forms is empty" in new TestFixtureJSONLineFormat {
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
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant
          ) wasCalled once
        }
      }

      "consolidate all form submissions into a single consolidation file" in new TestFixtureJSONLineFormat {
        //when

        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          val files = consolidationResult.get.reportFiles
          files.map(_.getName) shouldBe Array("report-0.xls")
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList shouldEqual List(FormJsonLineFilePartWriter.toJson(forms.head))
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            None,
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant
          ) wasCalled once
        }
      }

      "consolidate form submissions into multiple files" in new TestFixtureJSONLineFormat {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _reportPerFileSizeInBytes: Long = maxReportFileSize
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
              lines.head shouldEqual FormJsonLineFilePartWriter.toJson(form)
              fileSource.close
          }
        }
      }

      "consolidate form submissions, starting with ObjectId from previous run" in new TestFixtureJSONLineFormat {

        val consolidatorJobData = ConsolidatorJobData(
          projectId,
          now,
          now,
          Some(BSONObjectID.generate()),
          None,
          Some("previous-envelope-id")
        )
        mockConsolidatorJobDataRepository.findRecentLastObjectId(*)(*) shouldReturn Future.successful(
          Right(Some(consolidatorJobData))
        )

        val future = consolidatorService.doConsolidation(reportDir, formConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          val files = consolidationResult.get.reportFiles
          files.map(_.getName) shouldBe Array("report-0.xls")
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList shouldEqual List(FormJsonLineFilePartWriter.toJson(forms.head))
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasCalled once
          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            consolidatorJobData.lastObjectId,
            now.atZone(ZoneId.systemDefault()).minusSeconds(5).toInstant
          ) wasCalled once
        }
      }

      "consolidate form submissions, with user form consolidator params" in new TestFixtureJSONLineFormat {
        val startInstant = now.minus(2, ChronoUnit.DAYS)
        val endInstant = now.minus(1, ChronoUnit.DAYS)
        val userFormConsolidatorParams =
          ManualFormConsolidatorParams(
            projectId,
            format,
            FileUpload(classificationType, businessArea),
            startInstant,
            endInstant)
        val future = consolidatorService.doConsolidation(reportDir, userFormConsolidatorParams).unsafeToFuture()

        whenReady(future) { consolidationResult =>
          consolidationResult.isDefined shouldBe true
          consolidationResult.get.lastObjectId shouldBe forms.last.id
          consolidationResult.get.count shouldBe noOfForms

          val files = consolidationResult.get.reportFiles
          files.map(_.getName) shouldBe Array("report-0.xls")
          val fileSource = scala.io.Source.fromFile(files.head, "UTF-8")
          fileSource.getLines().toList shouldEqual List(FormJsonLineFilePartWriter.toJson(forms.head))
          fileSource.close

          mockConsolidatorJobDataRepository.findRecentLastObjectId(projectId)(*) wasNever called

          mockFormRepository.formsSource(
            projectId,
            _batchSize,
            Some(BSONObjectID.fromTime(startInstant.toEpochMilli)),
            endInstant
          ) wasCalled once
        }
      }

      "consolidate form submissions into multiple files (csv format)" in new TestFixtureCSVFormat {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _reportPerFileSizeInBytes: Long = maxReportFileSize

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
              file.getName shouldBe s"report-$index.csv"
              val fileSource = scala.io.Source.fromFile(file, "UTF-8")
              val lines = fileSource.getLines().toList
              lines.head shouldBe FormCSVFilePartWriter.toCSV(headers)
              lines(1) shouldBe FormCSVFilePartWriter.toCSV(form, headers)
              fileSource.close
          }
        }
      }

      "consolidate form submissions in multiple files (xlsx format)" in new TestFixtureXLSXFormat {
        //given
        override lazy val noOfForms: Int = 2
        override lazy val _reportPerFileSizeInBytes: Long = maxReportFileSize
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
              file.getName shouldBe s"report-$index.xlsx"
              val lines = excelFileRows(file)
              lines.head shouldBe headers
              lines(1) shouldBe headers.flatMap(h => form.formData.filter(_.id == h).map(_.value))
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
