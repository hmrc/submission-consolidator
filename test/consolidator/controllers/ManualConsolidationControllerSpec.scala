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

package consolidator.controllers

import java.time.LocalDate

import akka.actor.{ Actor, ActorSystem, Props }
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{ ImplicitSender, TestKit }
import com.typesafe.akka.`extension`.quartz.MessageWithFireTime
import com.typesafe.config.ConfigFactory
import consolidator.FormConsolidatorActor.OK
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.Configuration
import play.api.mvc.Result
import play.api.test.{ FakeRequest, Helpers }

class ManualConsolidationControllerSpec
    extends TestKit(ActorSystem("ConsolidationControllerSpec")) with AnyWordSpecLike with Matchers
    with BeforeAndAfterAll with IdiomaticMockito with ArgumentMatchersSugar with ImplicitSender with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

  val config =
    Configuration(ConfigFactory.parseString("""
                                              |consolidator-jobs = [
                                              |    {
                                              |        id = "testConsolidatorJobId"
                                              |        params = {
                                              |            projectId = "testProjectId1"
                                              |            classificationType = "testClassificationType1"
                                              |            businessArea = "testBusinessArea1"
                                              |        }
                                              |        # run every 2 seconds
                                              |        cron = "*/2 * * ? * *"
                                              |    }
                                              |]
                                              |""".stripMargin))

  "consolidateAndSubmit" should {
    "consolidate forms and submit them" in {
      val actorRef = system.actorOf(Props(new MockFileConsolidatorActor()), "testConsolidatorJobId")
      println(actorRef)
      val consolidationController = new ManualConsolidationController(Helpers.stubControllerComponents(), config)

      val request = FakeRequest("POST", "/consolidate/testConsolidatorJobId/2020-01-01/2020-01-02")

      val result: Result = consolidationController
        .consolidateAndSubmit("testConsolidatorJobId", "2020-01-01", "2020-01-02")(
          request)
        .futureValue

      result.header.status shouldBe StatusCodes.NoContent.intValue
    }
  }

  class MockFileConsolidatorActor extends Actor {
    override def receive: Receive = {
      case MessageWithFireTime(_, _) =>
        sender() ! OK
    }
  }
}
