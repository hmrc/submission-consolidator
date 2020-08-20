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

package consolidator

import java.nio.file.Paths
import java.util.Date

import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import cats.data.NonEmptyList
import cats.effect.IO
import collector.repositories.DataGenerators
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import common.MetricsClient
import consolidator.FormConsolidatorActor.{ LockUnavailable, OK }
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.{ ConsolidatorService, SubmissionService }
import org.mockito.ArgumentMatchersSugar
import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.lock.LockRepository

import scala.concurrent.Future

class FormConsolidatorActorSpec
    extends TestKit(ActorSystem("FormConsolidatorActorSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll
    with IdiomaticMockito with ArgumentMatchersSugar with DataGenerators with ImplicitSender {

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  trait TestFixture {
    val mockConsolidatorService = mock[ConsolidatorService](withSettings.lenient())
    val mockFileUploaderService = mock[SubmissionService](withSettings.lenient())
    val mockConsolidatorJobDataRepository = mock[ConsolidatorJobDataRepository](withSettings.lenient())
    val mockLockRepository = mock[LockRepository](withSettings.lenient())
    val mockMetricsClient = mock[MetricsClient](withSettings.lenient())

    val projectId = "some-project-id"
    val lastObjectId = BSONObjectID.generate()
    val outputPath = Paths.get("some-output-path")
    val envelopeId = "some-envelope-id"
    val consolidatorJobParam = ConsolidatorJobParam(projectId, "some-classification", "some-business-area")

    val actor = system.actorOf(
      FormConsolidatorActor
        .props(
          mockConsolidatorService,
          mockFileUploaderService,
          mockConsolidatorJobDataRepository,
          mockLockRepository,
          mockMetricsClient)
    )

    val messageWithFireTime = MessageWithFireTime(consolidatorJobParam, new Date())

    mockLockRepository.lock(*, *, *) shouldReturn Future.successful(true)
    mockLockRepository.renew(*, *, *) shouldReturn Future.successful(true)
    mockLockRepository.releaseLock(*, *) shouldReturn Future.successful(())

    def assertConsolidatorData(
      lastObjectId: Option[BSONObjectID],
      error: Option[String],
      envelopeId: Option[String]) = {
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
        mockConsolidatorService.doConsolidation(*) shouldReturn IO.pure(None)
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

        actor ! messageWithFireTime

        expectMsg(OK)

        mockConsolidatorService.doConsolidation(projectId) wasCalled once
        mockFileUploaderService.submit(*, consolidatorJobParam) wasNever called
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once

        assertConsolidatorData(None, None, None)
      }

      "skip consolidation if lock is not available" in new TestFixture {

        mockLockRepository.lock(*, *, *) shouldReturn Future.successful(false)

        actor ! messageWithFireTime

        expectMsg(LockUnavailable)
      }

      "consolidate forms and return OK" in new TestFixture {

        mockConsolidatorService.doConsolidation(*) shouldReturn IO.pure(
          Some(ConsolidationResult(Some(lastObjectId), 1, outputPath))
        )
        mockFileUploaderService.submit(*, *) shouldReturn IO.pure(NonEmptyList.of(envelopeId))
        mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

        actor ! messageWithFireTime

        expectMsg(OK)

        mockConsolidatorService.doConsolidation(projectId) wasCalled once
        mockFileUploaderService.submit(outputPath, consolidatorJobParam) wasCalled once
        mockMetricsClient.recordDuration(s"consolidator.$projectId.run", *) wasCalled once
        mockMetricsClient.markMeter(s"consolidator.$projectId.success") wasCalled once // success
        mockMetricsClient.markMeter(s"consolidator.$projectId.formCount", 1) wasCalled once // success
        assertConsolidatorData(Some(lastObjectId), None, Some(envelopeId))
      }

      "ConsolidatorService fails" should {

        "return the error message" in new TestFixture {
          mockConsolidatorService.doConsolidation(*) shouldReturn IO.raiseError(new Exception("consolidation error"))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() {
            case t: Throwable =>
              t.getMessage shouldBe "consolidation error"
              mockFileUploaderService.submit(outputPath, consolidatorJobParam) wasNever called
              mockMetricsClient.markMeter(s"consolidator.$projectId.failed") wasCalled once
              assertConsolidatorData(None, Some("consolidation error"), None)
          }
        }
      }

      "FileUploaderService fails" should {

        "return the error message" in new TestFixture {
          mockConsolidatorService.doConsolidation(*) shouldReturn IO.pure(
            Some(ConsolidationResult(Some(lastObjectId), 1, outputPath))
          )
          mockFileUploaderService.submit(*, *) shouldReturn IO.raiseError(new Exception("file upload error"))
          mockConsolidatorJobDataRepository.add(*)(*) shouldReturn Future.successful(Right(()))

          actor ! messageWithFireTime

          expectMsgPF() {
            case t: Throwable =>
              t.getMessage shouldBe "file upload error"
              mockConsolidatorService.doConsolidation(projectId) wasCalled once
              mockFileUploaderService.submit(outputPath, consolidatorJobParam) wasCalled once
              assertConsolidatorData(None, Some("file upload error"), None)
          }
        }
      }
    }
  }
}
