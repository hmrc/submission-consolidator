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
package collector

import org.scalatest.time.{ Millis, Seconds, Span }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.{ Application, Configuration }
import play.api.inject.guice.GuiceApplicationBuilder
import collector.repositories.FormRepository
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await.ready
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class AddFormSpec extends ITSpec {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override implicit val patienceConfig = PatienceConfig(Span(10, Seconds), Span(1, Millis))

  override def beforeEach(): Unit =
    ready(app.injector.instanceOf[FormRepository].removeAll(), 5.seconds)

  override def fakeApplication(): Application = {
    val config =
      Configuration(
        ConfigFactory
          .parseString("""
                         | consolidator-jobs = []
                         |""".stripMargin)
          .withFallback(baseConfig.underlying)
      )
    logger.info(s"configuration=$config")
    GuiceApplicationBuilder()
      .configure(config)
      .build()
  }

  "POST - /form" when {
    "form body is valid" should {
      "save the form in the mongodb submission-consolidator collection" in {
        val future = wsClient
          .url(baseUrl)
          .withHttpHeaders("Content-Type" -> "application/json")
          .post(APIFormStubs.validForm)
        whenReady(future) { response =>
          response.status shouldBe 200
        }
      }
    }

    "form body is invalid" should {
      "return BadRequest with error details" in {
        val future = wsClient
          .url(baseUrl)
          .withHttpHeaders("Content-Type" -> "application/json")
          .post(APIFormStubs.formEmptySubmissionRef)
        whenReady(future) { response =>
          response.status shouldBe 400
          response.body shouldBe "{\"code\":\"REQUEST_VALIDATION_FAILED\",\"message\":\"Request body failed validation\",\"fieldErrors\":[{\"path\":\"/submissionRef\",\"message\":\"Must confirm to the format XXXX-XXXX-XXXX, where X is a upper-case alphabet or a number\"}]}"
        }
      }
    }
  }
}
