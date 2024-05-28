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

import org.apache.pekko.actor.{ ActorSystem, ClassicActorSystemProvider }
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import collector.repositories.FormRepository
import collector.{ APIFormStubs, ITSpec }
import com.github.tomakehurst.wiremock.client.WireMock._
import com.typesafe.config.ConfigFactory
import org.mongodb.scala.Document
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Millis, Seconds, Span }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{ Application, Configuration }
import uk.gov.hmrc.objectstore.client.RetentionPeriod.OneWeek
import uk.gov.hmrc.objectstore.client.config.ObjectStoreClientConfig
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.play.test.stub

import java.util.UUID.randomUUID
import scala.concurrent.Await.ready
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ScheduledConsolidatorSpec extends ITSpec with Eventually {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(50, Seconds), Span(1, Millis))

  override def beforeEach(): Unit =
    ready(app.injector.instanceOf[FormRepository].collection.deleteMany(Document()).toFuture(), 5.seconds)

  override def fakeApplication(): Application = {
    val configOverride = s"""
                            | consolidator-jobs = [
                            |    {
                            |        id = "some-project-id-job"
                            |        params = {
                            |            projectId = "some-project-id"
                            |            classificationType = "some-classification-type"
                            |            businessArea = "some-business-area"
                            |            untilTime = "now"
                            |        }
                            |        # run every 2 seconds
                            |        cron = "*/2 * * ? * *"
                            |    }
                            | ]
                            |
                            | microservice {
                            |
                            |  services {
                            |
                            |    object-store {
                            |        host = localhost
                            |        port = $wiremockPort
                            |    }
                            |
                            |    sdes {
                            |      host = localhost
                            |      port = $wiremockPort
                            |      base-path = "/sdes-stub"
                            |      api-key = "client-id"
                            |      information-type = "1670499847785"
                            |      recipient-or-sender = "477099564866"
                            |      file-location-url = "http://localhost:8464/object-store/object/"
                            |    }
                            |  }
                            | }
                            | object-store {
                            |    enable = true
                            |    default-retention-period = "6-months"
                            |    zip-directory = "sdes"
                            | }
                            |""".stripMargin
    val config =
      Configuration(
        ConfigFactory
          .parseString(configOverride)
          .withFallback(baseConfig.underlying)
      )

    val osBaseUrl = s"http://localhost:$wiremockPort/object-store"
    val owner = "owner"
    val token = s"token-${randomUUID().toString}"
    val objectStoreConfig = ObjectStoreClientConfig(osBaseUrl, owner, token, OneWeek)

    implicit val system: ActorSystem = ActorSystem()

    implicit def matFromSystem(implicit provider: ClassicActorSystemProvider): Materializer =
      SystemMaterializer(provider.classicSystem).materializer

    lazy val objectStoreStub = new stub.StubPlayObjectStoreClient(objectStoreConfig)

    GuiceApplicationBuilder()
      .configure(config)
      .bindings(bind(classOf[PlayObjectStoreClient]).to(objectStoreStub))
      .build()
  }

  override def beforeAll(): Unit = {
    wireMockServer.start()
    configureFor("localhost", wiremockPort)
  }

  override def afterAll(): Unit =
    wireMockServer.stop()

  "consolidator" when {
    "forms are available for a configured project" should {
      "run consolidator job to consolidate the forms into a single file and submit the data to DMS using object-store" in {

        wiremockStubs()
        wsClient
          .url(baseUrl + "/form")
          .withHttpHeaders("Content-Type" -> "application/json")
          .post(APIFormStubs.validForm)
          .futureValue

        eventually {
          verify(postRequestedFor(urlEqualTo("/sdes-stub/notification/fileready")))
          verify(postRequestedFor(urlEqualTo("/object-store/object-store/ops/zip")))
        }
      }
    }
  }
}
