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

package consolidator.proxies

import java.io.File
import java.net.ConnectException

import common.{ ContentType, WSHttpClient }
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileUploadFrontEndProxySpec
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
    val fileUploadFrontEndProxy = new FileUploadFrontEndProxy(fileUploadConfig, mockWSHttpClient)

    val file = new File("some-file")
    lazy val responseStatus: Int = 200
    lazy val responseBody: String = ""
    lazy val response = Future.successful(
      HttpResponse(
        status = responseStatus,
        body = responseBody
      )
    )
    mockWSHttpClient.POSTFile(*, *, *[ContentType])(*) shouldReturn response
  }

  "upload" when {
    "request is successful" should {
      "return Unit" in new TestFixture {
        val future =
          fileUploadFrontEndProxy.upload("some-envelope-id", FileId("some-file-id"), file, ContentType.`text/plain`)
        whenReady(future) { result =>
          result shouldBe Right(())
          mockWSHttpClient.POSTFile(
            s"${fileUploadConfig.fileUploadFrontendBaseUrl}/file-upload/upload/envelopes/some-envelope-id/files/some-file-id",
            file,
            ContentType.`text/plain`
          )(*) wasCalled once
        }
      }
    }

    "request is non 201 http status" should {
      "return GenericFileUploadError" in new TestFixture {
        override lazy val responseStatus: Int = 400
        override lazy val responseBody: String = "some error"

        val future =
          fileUploadFrontEndProxy.upload("some-envelope-id", FileId("some-file-id"), file, ContentType.`text/plain`)

        whenReady(future) { result =>
          result shouldBe Left(GenericFileUploadError(
            "File upload failed [envelopeId=some-envelope-id, fileId=some-file-id, file=some-file, responseStatus=400, responseBody=some error]"))
        }
      }
    }

    "request fails with Connection exception" should {
      "return GenericFileUploadError" in new TestFixture {
        override lazy val response = Future.failed(new ConnectException("connection failed"))

        val future =
          fileUploadFrontEndProxy.upload("some-envelope-id", FileId("some-file-id"), file, ContentType.`text/plain`)

        whenReady(future) { result =>
          result shouldBe Left(
            GenericFileUploadError("File upload failed [error=java.net.ConnectException: connection failed]"))
        }
      }
    }
  }
}
