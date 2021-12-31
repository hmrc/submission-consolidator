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

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import collector.{APIFormStubs, ITSpec}
import collector.repositories.FormRepository
import com.github.tomakehurst.wiremock.client.WireMock.{configureFor, postRequestedFor, urlEqualTo, verify}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.slf4j.{Logger, LoggerFactory}
import play.api.{Application, Configuration}
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.Await.ready
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ManualConsolidatorSpec extends ITSpec with Eventually {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

  private val DATE_FORMAT = DateTimeFormatter.ISO_DATE

  override def beforeEach(): Unit =
    ready(app.injector.instanceOf[FormRepository].removeAll(), 5.seconds)

  override def beforeAll(): Unit = {
    wireMockServer.start()
    configureFor("localhost", wiremockPort)
  }

  override def afterAll(): Unit =
    wireMockServer.stop()

  override def fakeApplication(): Application = {
    val configOverride = s"""
                            | consolidator-jobs = [
                            |    {
                            |        id = "some-project-id-job"
                            |        params = {
                            |            projectId = "some-project-id"
                            |            classificationType = "some-classification-type"
                            |            businessArea = "some-business-area"
                            |        }
                            |        # never run
                            |        cron = "0 0 0 1 1 ? 2099"
                            |    }
                            | ]
                            |
                            | microservice {
                            |
                            |  services {
                            |
                            |    file-upload {
                            |        host = localhost
                            |        port = $wiremockPort
                            |    }
                            |
                            |    file-upload-frontend {
                            |        host = localhost
                            |        port = $wiremockPort
                            |    }
                            |  }
                            | }
                            |""".stripMargin
    val config =
      Configuration(
        ConfigFactory
          .parseString(configOverride)
          .withFallback(baseConfig.underlying)
      )

    GuiceApplicationBuilder()
      .configure(config)
      .build()
  }

  "POST - /consolidate" when {
    "request is valid" should {
      "consolidate forms and submit" in {
        wiremockStubs()
        wsClient
          .url(baseUrl + "/form")
          .withHttpHeaders("Content-Type" -> "application/json")
          .post(APIFormStubs.validForm)
          .futureValue

        val future = wsClient
          .url(baseUrl+s"/consolidate/some-project-id-job/${LocalDate.now().format(DATE_FORMAT)}/${LocalDate.now().format(DATE_FORMAT)}")
          .withHttpHeaders("Content-Type" -> "application/json")
          .post(APIFormStubs.formEmptySubmissionRef)

        whenReady(future) { _ =>
          verify(postRequestedFor(urlEqualTo("/file-upload/envelopes")))
          verify(postRequestedFor(urlEqualTo("/file-upload/upload/envelopes/some-envelope-id/files/xmlDocument")))
          verify(postRequestedFor(urlEqualTo("/file-upload/upload/envelopes/some-envelope-id/files/pdf")))
          verify(postRequestedFor(urlEqualTo("/file-upload/upload/envelopes/some-envelope-id/files/report-0")))
          verify(postRequestedFor(urlEqualTo("/file-routing/requests")))
        }
      }
    }
  }
}
