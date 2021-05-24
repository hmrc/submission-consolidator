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

package consolidator.scheduler

import java.util.Date

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestKit, TestProbe }
import com.typesafe.akka.extension.quartz.MessageWithFireTime
import com.typesafe.config.ConfigFactory
import consolidator.services.{ ConsolidationFormat, ScheduledFormConsolidatorParams }
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
      val config =
        Configuration(ConfigFactory.parseString("""
                                                  |consolidator-jobs = [
                                                  |    {
                                                  |        id = "some-project-id-1-job"
                                                  |        params = {
                                                  |            projectId = "some-project-id-1"
                                                  |            classificationType = "some-classification-type-1"
                                                  |            businessArea = "some-business-area-1"
                                                  |        }
                                                  |        # run every 2 seconds
                                                  |        cron = "*/2 * * ? * *"
                                                  |    },
                                                  |    {
                                                  |        id = "some-project-id-2-job"
                                                  |        params = {
                                                  |            projectId = "some-project-id-2"
                                                  |            classificationType = "some-classification-type-2"
                                                  |            businessArea = "some-business-area-2"
                                                  |            format = "csv"
                                                  |            untilTime = "previous_day"
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
      jobParams should contain(
        ScheduledFormConsolidatorParams(
          "some-project-id-1",
          "some-classification-type-1",
          "some-business-area-1",
          ConsolidationFormat.jsonl,
          UntilTime.now))
      jobParams should contain(
        ScheduledFormConsolidatorParams(
          "some-project-id-2",
          "some-classification-type-2",
          "some-business-area-2",
          ConsolidationFormat.csv,
          UntilTime.previous_day))

      jobScheduler.shutdown(true)
    }
  }
}

class TestJobActor(senderRef: ActorRef) extends Actor {
  override def receive: Receive = {
    case MessageWithFireTime(p: ScheduledFormConsolidatorParams, _: Date) =>
      senderRef ! p
  }
}
