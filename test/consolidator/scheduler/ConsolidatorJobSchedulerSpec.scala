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

package consolidator.scheduler

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestKit, TestProbe }
import com.typesafe.config.ConfigFactory
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.Configuration

import scala.concurrent.duration._

class ConsolidatorJobSchedulerSpec
    extends TestKit(ActorSystem("JobSchedulerSpec")) with AnyWordSpecLike with IdiomaticMockito with BeforeAndAfterAll
    with Matchers with ImplicitSender {

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  "scheduleJobs" should {
    "schedule and execute the jobs based on the provided config config" in {
      val config = Configuration(ConfigFactory.parseString("""
                                                             |consolidator-job-config = [
                                                             |    {
                                                             |        id = "some-project-id-1-job"
                                                             |        params = {
                                                             |            projectId = "some-project-id-1"
                                                             |            destinationQueue = "some-queue-1"
                                                             |        }
                                                             |        # run every 2 seconds
                                                             |        cron = "*/2 * * ? * *"
                                                             |    },
                                                             |    {
                                                             |        id = "some-project-id-2-job"
                                                             |        params = {
                                                             |            projectId = "some-project-id-2"
                                                             |            destinationQueue = "some-queue-2"
                                                             |        }
                                                             |        # run every 2 seconds
                                                             |        cron = "*/2 * * ? * *"
                                                             |    }
                                                             |]
                                                             |""".stripMargin))
      val testProbe = TestProbe()
      val jobScheduler = new ConsolidatorJobScheduler(config).scheduleJobs(Props(new TestJobActor(testProbe.ref)))

      val jobParams = testProbe.receiveN(2, 3.seconds).toList
      jobParams.size shouldBe 2
      jobParams should contain(ConsolidatorJobParam("some-project-id-1", "some-queue-1"))
      jobParams should contain(ConsolidatorJobParam("some-project-id-2", "some-queue-2"))

      jobScheduler.shutdown(true)
    }
  }
}

class TestJobActor(senderRef: ActorRef) extends Actor {
  override def receive: Receive = {
    case p: ConsolidatorJobParam =>
      senderRef ! p
  }
}