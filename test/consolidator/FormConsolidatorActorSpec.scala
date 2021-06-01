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

package consolidator

import java.nio.file.Files.createDirectories
import java.nio.file.{ Path, Paths }
import java.util.Date
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import cats.data.NonEmptyList
import cats.effect.IO
import collector.repositories.DataGenerators
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import common.MetricsClient
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.TestHelper.createFileInDir
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.{ FileUpload, S3, UntilTime }
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.{ ConsolidationFormat, ConsolidatorService, DeleteDirService, FileUploadSubmissionResult, FileUploadSubmissionService, S3SubmissionResult, S3SubmissionService, ScheduledFormConsolidatorParams, SubmissionResult }
import org.mockito.ArgumentMatchersSugar
import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.lock.LockRepository

import java.io.File
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId, ZonedDateTime }
import scala.concurrent.Future

class FormConsolidatorActorSpec
    extends TestKit(ActorSystem("FormConsolidatorActorSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll
    with IdiomaticMockito with ArgumentMatchersSugar with DataGenerators with ImplicitSender {

  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  trait TestFixture {
    val mockConsolidatorService = mock[ConsolidatorService](withSettings.lenient())
    val mockFileUploadSubmissionService = mock[FileUploadSubmissionService](withSettings.lenient())
    val mockS3SubmissionService = mock[S3SubmissionService](withSettings.lenient())
    val mockConsolidatorJobDataRepository = mock[ConsolidatorJobDataRepository](withSettings.lenient())
    val mockLockRepository = mock[LockRepository](withSettings.lenient())
    val mockMetricsClient = mock[MetricsClient](withSettings.lenient())
    val mockDeleteDirService = mock[DeleteDirService](withSettings.lenient())

    val now = Instant.now()
    val zonedNow: ZonedDateTime = now.atZone(ZoneId.systemDefault())
    val projectId = "some-project-id"
    val lastObjectId = BSONObjectID.generate()
    val reportDir = createReportDir(projectId, zonedNow)
    val numberOfReportFiles: Int = 1
    (0 until numberOfReportFiles).foreach(i => createFileInDir(reportDir, s"report-$i.txt", 10))
    val reportFiles = reportDir.toFile.listFiles().toList
    val envelopeId = "some-envelope-id"
    lazy val consolidatorParams = ScheduledFormConsolidatorParams(
      projectId,
      ConsolidationFormat.jsonl,
      FileUpload("some-classification", "some-business-area"),
      UntilTime.now
    )

    val actor = system.actorOf(
      FormConsolidatorActor
        .props(
          mockConsolidatorService,
          mockFileUploadSubmissionService,
          mockS3SubmissionService,
          mockConsolidatorJobDataRepository,
          mockLockRepository,
          mockMetricsClient,
          mockDeleteDirService
        )
    )

    lazy val messageWithFireTime = MessageWithFireTime(consolidatorParams, new Date(now.toEpochMilli))

    mockLockRepository.lock(*, *, *) shouldReturn Future.successful(true)
    mockLockRepository.renew(*, *, *) shouldReturn Future.successful(true)
    mockLockRepository.releaseLock(*, *) shouldReturn Future.successful(())

    def assertConsolidatorData(
      lastObjectId: Option[BSONObjectID],
      error: Option[String],
      envelopeId: Option[String],
      submissionResult: Option[SubmissionResult]
    ) = {
      val consolidatorJobDataCaptor = ArgCaptor[ConsolidatorJobData]
      mockConsolidatorJobDataRepository.add(consolidatorJobDataCaptor)(*) wasCalled once
      consolidatorJobDataCaptor.value.projectId shouldBe projectId
      consolidatorJobDataCaptor.value.lastObjectId shouldBe lastObjectId
      consolidatorJobDataCaptor.value.error shouldBe error
      consolidatorJobDataCaptor.value.envelopeId shouldBe envelopeId
      consolidatorJobDataCaptor.value.submissionResult shouldBe submissionResult
      consolidatorJobDataCaptor.value.endTimestamp.isAfter(
        consolidatorJobDataCaptor.value.startTimestamp
      ) shouldBe true
    }
  }

  "FormConsolidatorActor" when {

    "given a ConsolidatorJobParam message" should {

      "skip consolidation if forms are empty and return OK" in new TestFixture {
        mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(None)
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
        mockDeleteDirService.deleteDir(*) returns Future.successful(Right(()))

        actor ! messageWithFireTime

        expectMsg(OK)

        mockDeleteDirService.deleteDir(reportDir) wasCalled once
        mockConsolidatorService.doConsolidation(reportDir, consolidatorParams) wasCalled once
        mockFileUploadSubmissionService.submit(*, *, *, *) wasNever called
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once

        assertConsolidatorData(None, None, None, None)
      }

      "skip consolidation if lock is not available" in new TestFixture {

        mockLockRepository.lock(*, *, *) shouldReturn Future.successful(false)

        actor ! messageWithFireTime

        expectMsg(LockUnavailable)

        mockDeleteDirService.deleteDir(*) wasNever called
      }

      "consolidate forms and return OK for fileUpload destination" in new TestFixture {

        mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(
          Some(ConsolidationResult(lastObjectId, 1, reportFiles))
        )
        mockFileUploadSubmissionService.submit(*, *, *, *) shouldReturn IO.pure(
          FileUploadSubmissionResult(NonEmptyList.of(envelopeId))
        )
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
        mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))

        actor ! messageWithFireTime

        expectMsg(OK)

        mockDeleteDirService.deleteDir(reportDir) wasCalled once
        mockConsolidatorService.doConsolidation(reportDir, consolidatorParams) wasCalled once
        mockFileUploadSubmissionService.submit(
          reportFiles,
          consolidatorParams.projectId,
          consolidatorParams.format,
          consolidatorParams.destination.asInstanceOf[FileUpload]
        ) wasCalled once
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once
        mockMetricsClient.markMeter(s"consolidator.$projectId.success") wasCalled once // success
        mockMetricsClient.markMeter(s"consolidator.$projectId.formCount", 1) wasCalled once // success
        assertConsolidatorData(
          Some(lastObjectId),
          None,
          Some(envelopeId),
          Some(FileUploadSubmissionResult(NonEmptyList.one(envelopeId))))
      }

      "consolidate forms and return OK for s3 destination" in new TestFixture {

        override lazy val consolidatorParams = ScheduledFormConsolidatorParams(
          projectId,
          ConsolidationFormat.jsonl,
          S3(new URI("http://s3"), "some-bucket"),
          UntilTime.now
        )

        mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(
          Some(ConsolidationResult(lastObjectId, 1, reportFiles))
        )
        mockS3SubmissionService.submit(*, *) shouldReturn IO.pure(
          S3SubmissionResult(NonEmptyList.fromListUnsafe(reportFiles.map(f => s3SubmissionReportFileName(zonedNow, f))))
        )
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
        mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))

        actor ! messageWithFireTime

        expectMsg(OK)

        mockDeleteDirService.deleteDir(reportDir) wasCalled once
        mockConsolidatorService.doConsolidation(reportDir, consolidatorParams) wasCalled once
        mockS3SubmissionService.submit(
          reportFiles,
          consolidatorParams.destination.asInstanceOf[S3]
        ) wasCalled once
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once
        mockMetricsClient.markMeter(s"consolidator.$projectId.success") wasCalled once // success
        mockMetricsClient.markMeter(s"consolidator.$projectId.formCount", 1) wasCalled once // success
        assertConsolidatorData(
          Some(lastObjectId),
          None,
          Some(""),
          Some(S3SubmissionResult(NonEmptyList.one(s3SubmissionReportFileName(zonedNow, new File("report-0.txt"))))))
      }

      "ConsolidatorService fails" should {

        "return the error message" in new TestFixture {
          mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.raiseError(new Exception("consolidation error"))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
          mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() {
            case t: Throwable =>
              t.getMessage shouldBe "consolidation error"
              mockFileUploadSubmissionService.submit(
                reportFiles,
                consolidatorParams.projectId,
                consolidatorParams.format,
                consolidatorParams.destination.asInstanceOf[FileUpload]
              ) wasNever called
              mockDeleteDirService.deleteDir(reportDir) wasCalled once
              mockMetricsClient.markMeter(s"consolidator.$projectId.failed") wasCalled once
              assertConsolidatorData(None, Some("consolidation error"), None, None)
          }
        }
      }

      "FileUploadSubmissionService fails" should {

        "return the error message" in new TestFixture {
          mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(
            Option(ConsolidationResult(lastObjectId, 1, reportFiles))
          )
          mockFileUploadSubmissionService.submit(*, *, *, *) shouldReturn IO.raiseError(
            new Exception("file upload error")
          )
          mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() {
            case t: Throwable =>
              t.getMessage shouldBe "file upload error"
              mockConsolidatorService.doConsolidation(reportDir, consolidatorParams) wasCalled once
              mockFileUploadSubmissionService.submit(
                reportFiles,
                consolidatorParams.projectId,
                consolidatorParams.format,
                consolidatorParams.destination.asInstanceOf[FileUpload]
              ) wasCalled once
              mockDeleteDirService.deleteDir(reportDir) wasCalled once
              assertConsolidatorData(None, Some("file upload error"), None, None)
          }
        }
      }

      "S3SubmissionService fails" should {

        "return the error message" in new TestFixture {
          override lazy val consolidatorParams = ScheduledFormConsolidatorParams(
            projectId,
            ConsolidationFormat.jsonl,
            S3(new URI("http://s3"), "some-bucket"),
            UntilTime.now
          )
          mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(
            Option(ConsolidationResult(lastObjectId, 1, reportFiles))
          )
          mockS3SubmissionService.submit(*, *) shouldReturn IO.raiseError(
            new Exception("S3 error")
          )
          mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() {
            case t: Throwable =>
              t.getMessage shouldBe "S3 error"
              mockConsolidatorService.doConsolidation(reportDir, consolidatorParams) wasCalled once
              mockS3SubmissionService.submit(
                reportFiles,
                consolidatorParams.destination.asInstanceOf[S3]
              ) wasCalled once
              mockDeleteDirService.deleteDir(reportDir) wasCalled once
              assertConsolidatorData(None, Some("S3 error"), None, None)
          }
        }
      }
    }
  }

  private def createReportDir(projectId: String, zonedDateTime: ZonedDateTime): Path =
    createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/submission-consolidator/$projectId-${DATE_TIME_FORMAT
        .format(zonedDateTime)}")
    )

  private def s3SubmissionReportFileName(zonedDateTime: ZonedDateTime, file: File) = {
    val extIndex = file.getName.lastIndexOf(".")
    file.getName.substring(0, extIndex) + "-" + DATE_TIME_FORMAT.format(zonedDateTime) + file.getName
      .substring(extIndex)
  }
}
