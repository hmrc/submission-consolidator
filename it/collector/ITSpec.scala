/*
 * Copyright 2022 HM Revenue & Customs
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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, stubFor, urlEqualTo}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Configuration
import play.api.libs.ws.WSClient
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.objectstore.client.{Md5Hash, ObjectSummaryWithMd5}

import java.time.Instant
import scala.util.Random

trait ITSpec
    extends AnyWordSpecLike with GuiceOneServerPerSuite with Matchers with BeforeAndAfterAll
    with BeforeAndAfterEach with ScalaFutures {

  private val mongoDbName: String = "test-" + this.getClass.getSimpleName

  lazy val baseConfig: Configuration = Configuration(ConfigFactory.parseString(
    s"""| auditing {
        |   enabled = false
        | }
        | mongodb {
        |   uri = "mongodb://localhost:27017/$mongoDbName"
        | }""".stripMargin).withFallback(ConfigFactory.load()))

  lazy val baseUrl: String =
    s"http://localhost:$port/submission-consolidator"
  lazy val wsClient = app.injector.instanceOf[WSClient]

  val wiremockPort = 10000 + Random.nextInt(10000)
  val wireMockServer = new WireMockServer(options().port(wiremockPort))

  def wiremockStubs() = {
    val objectSummary = ObjectSummaryWithMd5(File("test.txt"), 10L, Md5Hash("md5"), Instant.now())

    stubFor(
      post(urlEqualTo("object-store/object-store/ops/zip"))
        .willReturn(
          aResponse()
            .withStatus(201)
            .withBody(objectSummary.toString)
        )
    )
  }
}
