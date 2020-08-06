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

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import cats.effect.IO
import collector.repositories.DataGenerators
import consolidator.FormConsolidatorActor.OK
import consolidator.dms.FileUploaderService
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.ConsolidatorService
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class FormConsolidatorActorSpec
    extends TestKit(ActorSystem("FormConsolidatorActorSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll
    with IdiomaticMockito with ArgumentMatchersSugar with DataGenerators with ImplicitSender {

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  trait TestFixture {
    val mockConsolidatorService = mock[ConsolidatorService]
    val mockFileUploaderService = mock[FileUploaderService]
    val projectId = "some-project-id"
    val actor = system.actorOf(FormConsolidatorActor.props(mockConsolidatorService, mockFileUploaderService))
  }

  "FormConsolidatorActor" when {
    "given a ConsolidatorJobParam message" should {
      "consolidate forms and return OK" in new TestFixture {
        val testFile = new File("test.txt")
        mockConsolidatorService.doConsolidation(*) shouldReturn IO.pure(Some(testFile))
        mockFileUploaderService.upload(*) shouldReturn IO.pure(())

        actor ! ConsolidatorJobParam(projectId, "SOME_QUEUE")

        expectMsg(OK)

        mockConsolidatorService.doConsolidation(projectId) wasCalled once
        mockFileUploaderService.upload(testFile) wasCalled once
      }
    }

    "given a ConsolidatorJobParam message and ConsolidatorService fails" should {
      "return the error message" in new TestFixture {
        mockConsolidatorService.doConsolidation(*) shouldReturn IO.raiseError(new Exception("consolidation error"))

        actor ! ConsolidatorJobParam(projectId, "SOME_QUEUE")

        expectMsgPF() {
          case t: Throwable =>
            t.getMessage shouldBe "consolidation error"
        }
      }
    }

    "given a ConsolidatorJobParam message and FileUploaderService fails" should {
      "return the error message" in new TestFixture {
        val testFile = new File("test.txt")
        mockConsolidatorService.doConsolidation(*) shouldReturn IO.pure(Some(testFile))
        mockFileUploaderService.upload(*) shouldReturn IO.raiseError(new Exception("file upload error"))

        actor ! ConsolidatorJobParam(projectId, "SOME_QUEUE")

        expectMsgPF() {
          case t: Throwable =>
            t.getMessage shouldBe "file upload error"
        }
      }
    }
  }
}
