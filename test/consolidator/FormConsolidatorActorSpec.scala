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

package consolidator

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ ImplicitSender, TestKit }
import cats.data.NonEmptyList
import cats.effect.IO
import collector.repositories.DataGenerators
import org.apache.pekko.extension.quartz.MessageWithFireTime
import common.MetricsClient
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.{ FileUpload, UntilTime }
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services._
import org.bson.types.ObjectId
import org.mockito.ArgumentMatchersSugar
import org.mockito.captor.ArgCaptor
import org.mockito.quality.Strictness
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.lock.{ Lock, MongoLockRepository }

import java.nio.file.Files.createDirectories
import java.nio.file.{ Path, Paths }
import java.text.SimpleDateFormat
import java.util.Date
import scala.concurrent.Future

class FormConsolidatorActorSpec
    extends TestKit(ActorSystem("FormConsolidatorActorSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll
    with IdiomaticMockito with ArgumentMatchersSugar with DataGenerators with ImplicitSender {

  private val DATE_TIME_FORMAT = new SimpleDateFormat("yyyyMMddHHmmssSSS")

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  trait TestFixture {
    val mockConsolidatorService = mock[ConsolidatorService](withSettings.strictness(Strictness.LENIENT))
    val mockFileUploaderService = mock[SubmissionService](withSettings.strictness(Strictness.LENIENT))
    val mockConsolidatorJobDataRepository =
      mock[ConsolidatorJobDataRepository](withSettings.strictness(Strictness.LENIENT))
    val mockLock = mock[Lock](withSettings.strictness(Strictness.LENIENT))
    val mockLockRepository = mock[MongoLockRepository](withSettings.strictness(Strictness.LENIENT))
    val mockMetricsClient = mock[MetricsClient](withSettings.strictness(Strictness.LENIENT))
    val mockDeleteDirService = mock[DeleteDirService](withSettings.strictness(Strictness.LENIENT))

    val now = new Date()
    val projectId = "some-project-id"
    val lastObjectId = ObjectId.get()
    val reportDir = createReportDir(projectId, now)
    val reportFiles = reportDir.toFile.listFiles().toList
    val envelopeId = "some-envelope-id"
    val schedulerFormConsolidatorParams = ScheduledFormConsolidatorParams(
      projectId,
      ConsolidationFormat.jsonl,
      FileUpload("some-classification", "some-business-area"),
      UntilTime.now
    )

    val actor = system.actorOf(
      FormConsolidatorActor
        .props(
          mockConsolidatorService,
          mockFileUploaderService,
          mockConsolidatorJobDataRepository,
          mockLockRepository,
          mockMetricsClient,
          mockDeleteDirService
        )
    )

    val messageWithFireTime = MessageWithFireTime(schedulerFormConsolidatorParams, now)

    mockLockRepository.takeLock(*, *, *) shouldReturn Future.successful(Some(mockLock))
    mockLockRepository.refreshExpiry(*, *, *) shouldReturn Future.successful(true)
    mockLockRepository.releaseLock(*, *) shouldReturn Future.successful(())

    def assertConsolidatorData(
      lastObjectId: Option[ObjectId],
      error: Option[String],
      envelopeId: Option[String]
    ) = {
      val consolidatorJobDataCaptor = ArgCaptor[ConsolidatorJobData]
      mockConsolidatorJobDataRepository.add(consolidatorJobDataCaptor)(*) wasCalled once
      consolidatorJobDataCaptor.value.projectId shouldBe projectId
      consolidatorJobDataCaptor.value.lastObjectId shouldBe lastObjectId
      consolidatorJobDataCaptor.value.error shouldBe error
      consolidatorJobDataCaptor.value.envelopeId shouldBe envelopeId
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
        mockConsolidatorService.doConsolidation(reportDir, schedulerFormConsolidatorParams) wasCalled once
        mockFileUploaderService.submit(*, *) wasNever called
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once

        assertConsolidatorData(None, None, None)
      }

      "skip consolidation if lock is not available" in new TestFixture {

        mockLockRepository.takeLock(*, *, *) shouldReturn Future.successful(None)

        actor ! messageWithFireTime

        expectMsg(LockUnavailable)

        mockDeleteDirService.deleteDir(*) wasNever called
      }

      "consolidate forms and return OK" in new TestFixture {

        mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(
          Some(ConsolidationResult(lastObjectId, 1, reportFiles))
        )
        mockFileUploaderService.submit(*, *) shouldReturn IO.pure(NonEmptyList.of(envelopeId))
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
        mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))

        actor ! messageWithFireTime

        expectMsg(OK)

        mockDeleteDirService.deleteDir(reportDir) wasCalled once
        mockConsolidatorService.doConsolidation(reportDir, schedulerFormConsolidatorParams) wasCalled once
        mockFileUploaderService.submit(reportFiles, schedulerFormConsolidatorParams) wasCalled once
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once
        mockMetricsClient.markMeter(s"consolidator.$projectId.success") wasCalled once // success
        mockMetricsClient.markMeter(s"consolidator.$projectId.formCount", 1) wasCalled once // success
        assertConsolidatorData(Some(lastObjectId), None, Some(envelopeId))
      }

      "ConsolidatorService fails" should {

        "return the error message" in new TestFixture {
          mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.raiseError(new Exception("consolidation error"))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))
          mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() { case t: Throwable =>
            t.getMessage shouldBe "consolidation error"
            mockFileUploaderService.submit(reportFiles, schedulerFormConsolidatorParams) wasNever called
            mockDeleteDirService.deleteDir(reportDir) wasCalled once
            mockMetricsClient.markMeter(s"consolidator.$projectId.failed") wasCalled once
            assertConsolidatorData(None, Some("consolidation error"), None)
          }
        }
      }

      "FileUploaderService fails" should {

        "return the error message" in new TestFixture {
          mockConsolidatorService.doConsolidation(*, *) shouldReturn IO.pure(
            Option(ConsolidationResult(lastObjectId, 1, reportFiles))
          )
          mockFileUploaderService.submit(*, *) shouldReturn IO.raiseError(new Exception("file upload error"))
          mockDeleteDirService.deleteDir(*) shouldReturn Future.successful(Right(()))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() { case t: Throwable =>
            t.getMessage shouldBe "file upload error"
            mockConsolidatorService.doConsolidation(reportDir, schedulerFormConsolidatorParams) wasCalled once
            mockFileUploaderService.submit(reportFiles, schedulerFormConsolidatorParams) wasCalled once
            mockDeleteDirService.deleteDir(reportDir) wasCalled once
            assertConsolidatorData(None, Some("file upload error"), None)
          }
        }
      }
    }
  }

  private def createReportDir(projectId: String, time: Date): Path =
    createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/submission-consolidator/$projectId-${DATE_TIME_FORMAT
        .format(time)}")
    )
}
