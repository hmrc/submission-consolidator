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
import play.api.http.HeaderNames.LOCATION
import play.api.libs.ws.WSClient

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
    s"http://localhost:$port/submission-consolidator/form"
  lazy val wsClient = app.injector.instanceOf[WSClient]

  val wiremockPort = 10000 + Random.nextInt(10000)
  val wireMockServer = new WireMockServer(options().port(wiremockPort))

  def wiremockStubs() = {
    stubFor(
      post(urlEqualTo("/file-upload/envelopes"))
        .willReturn(
          aResponse()
            .withStatus(201)
            .withHeader(LOCATION, "envelopes/some-envelope-id")
        )
    )

    stubFor(
      post(urlEqualTo("/file-routing/requests"))
        .willReturn(
          aResponse()
            .withStatus(201)
        )
    )

    stubFor(
      post(urlEqualTo("/file-upload/upload/envelopes/some-envelope-id/files/report-0"))
        .willReturn(
          aResponse()
            .withStatus(200)
        )
    )

    stubFor(
      post(urlEqualTo("/file-upload/upload/envelopes/some-envelope-id/files/xmlDocument"))
        .willReturn(
          aResponse()
            .withStatus(200)
        )
    )
  }
}
