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

package consolidator.proxies

import java.net.ConnectException

import common.WSHttpClient
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.mvc.Http.HeaderNames
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileUploadProxySpec
    extends AnyWordSpec with Matchers with IdiomaticMockito with ArgumentMatchersSugar with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))
  implicit lazy val hc = HeaderCarrier()

  trait TestFixture {
    val mockWSHttpClient = mock[WSHttpClient]
    val config = Configuration(
      "microservice.services.file-upload.host"          -> "localhost",
      "microservice.services.file-upload.port"          -> 1,
      "microservice.services.file-upload-frontend.host" -> "localhost",
      "microservice.services.file-upload-frontend.port" -> 2
    )
    val fileUploadConfig = new FileUploadConfig(new ServicesConfig(config))
    val fileUploadProxy = new FileUploadProxy(fileUploadConfig, mockWSHttpClient)
    val headers = Seq("Csrf-Token" -> "nocheck")
  }

  trait CreateEnvelopeFixture extends TestFixture {
    val request = CreateEnvelopeRequest(
      Metadata(application = "some-application"),
      Constraints(
        maxItems = 1,
        maxSize = "1MB",
        maxSizePerItem = "1MB",
        contentTypes = List("some-content-type"),
        allowZeroLengthFiles = false
      )
    )
    lazy val responseHeaders: Map[String, Seq[String]] = Map(
      HeaderNames.LOCATION -> Seq("localhost/file-upload/envelopes/some-envelope-id"))
    lazy val responseStatus: Int = 201
    lazy val responseBody: String = ""
    lazy val response = Future.successful(
      HttpResponse(
        status = responseStatus,
        body = responseBody,
        headers = responseHeaders
      )
    )
    mockWSHttpClient.POST(*, *, *)(*, *[HttpReads[HttpResponse]], *, *) shouldReturn response
  }

  "createEnvelope" when {
    "response is valid" should {
      "create the envelope and return the envelope id" in new CreateEnvelopeFixture {

        val future = fileUploadProxy.createEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Right("some-envelope-id")
          mockWSHttpClient.POST(s"${fileUploadConfig.fileUploadBaseUrl}/file-upload/envelopes", request, headers)(
            *,
            *[HttpReads[HttpResponse]],
            *,
            *) wasCalled once
        }
      }
    }

    "response location header is missing" should {
      "return LocationHeaderInvalid error" in new CreateEnvelopeFixture {
        override lazy val responseHeaders = Map.empty

        val future = fileUploadProxy.createEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Left(LocationHeaderMissingOrInvalid)
        }
      }
    }

    "response location header is invalid" should {
      "return LocationHeaderMissingOrInvalid error" in new CreateEnvelopeFixture {
        override lazy val responseHeaders = Map(HeaderNames.LOCATION -> Seq("invalid-value"))

        val future = fileUploadProxy.createEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Left(LocationHeaderMissingOrInvalid)
        }
      }
    }

    "response is non 201 http status" should {
      "return GenericFileUploadError" in new CreateEnvelopeFixture {
        override lazy val responseStatus = 400
        override lazy val responseBody = "some error"

        val future = fileUploadProxy.createEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Left(
            GenericFileUploadError(s"Create envelope failed [status=$responseStatus, body=$responseBody]"))
        }
      }
    }

    "http request fails with ConnectException" should {
      "return GenericFileUploadError" in new CreateEnvelopeFixture {
        override lazy val response = Future.failed(new ConnectException("connection failed"))

        val future = fileUploadProxy.createEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Left(
            GenericFileUploadError(s"Create envelope failed [error=java.net.ConnectException: connection failed]"))
        }
      }
    }
  }

  trait RouteEnvelopeFixture extends TestFixture {
    val request = RouteEnvelopeRequest("some-envelope-id", "some-application", "some-destination")

    lazy val responseStatus: Int = 201
    lazy val responseBody: String = ""
    lazy val response = Future.successful(
      HttpResponse(
        status = responseStatus,
        body = responseBody
      )
    )
    mockWSHttpClient.POST(*, *, *)(*, *[HttpReads[HttpResponse]], *, *) shouldReturn response
  }

  "routeEnvelope" when {

    "response is valid" should {
      "return Unit" in new RouteEnvelopeFixture {
        val future = fileUploadProxy.routeEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Right(())
          mockWSHttpClient.POST(s"${fileUploadConfig.fileUploadBaseUrl}/file-routing/requests", request, headers)(
            *,
            *[HttpReads[HttpResponse]],
            *,
            *) wasCalled once
        }
      }
    }

    "response is non 201 http status" should {
      "return GenericFileUploadError" in new RouteEnvelopeFixture {
        override lazy val responseStatus = 400
        override lazy val responseBody = "some error"

        val future = fileUploadProxy.routeEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Left(
            GenericFileUploadError(s"Route envelope failed [status=$responseStatus, body=$responseBody]"))
        }
      }
    }

    "http request fails with ConnectException" should {
      "return GenericFileUploadError" in new RouteEnvelopeFixture {
        override lazy val response = Future.failed(new ConnectException("connection failed"))

        val future = fileUploadProxy.routeEnvelope(request)

        whenReady(future) { result =>
          result shouldBe Left(
            GenericFileUploadError(s"Route envelope failed [error=java.net.ConnectException: connection failed]"))
        }
      }
    }
  }
}
