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

package consolidator.services

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import akka.util.ByteString
import common.Time
import consolidator.proxies.{ Constraints, CreateEnvelopeRequest, FileUploadError, FileUploadFrontEndProxy, FileUploadProxy, GenericFileUploadError, RouteEnvelopeRequest }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.SubmissionService.{ FileIds, SubmissionRef }
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SubmissionServiceSpec extends AnyWordSpec with IdiomaticMockito with ArgumentMatchersSugar with Matchers {

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  trait TestFixture {
    val formsFile = new File("some-file")
    val config = ConsolidatorJobParam("some-project-id", "some-classification-type", "some-business-area")
    val someEnvelopedId = "some-envelope-id"
    val someSubmissionRef = "SOMESUBMISSIONREF"
    val now = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now

    val mockFileUploadProxy = mock[FileUploadProxy](withSettings.lenient())
    val mockFileUploadFrontendProxy = mock[FileUploadFrontEndProxy](withSettings.lenient())
    val mockSubmissionRefGenerator = mock[SubmissionRefGenerator](withSettings.lenient())
    val metaDataDocument = Documents(
      Document(
        Header(
          someSubmissionRef,
          "jsonlines",
          "text/plain",
          true,
          "dfs",
          "DMS",
          s"$someSubmissionRef-${DATE_TIME_FORMAT.format(now.atZone(ZoneId.systemDefault()))}"
        ),
        Metadata(
          List(
            Attribute("classification_type", "string", List(config.classificationType)),
            Attribute("business_area", "string", List(config.businessArea)),
            Attribute("attachment_count", "int", List("2"))
          )
        )
      )
    )

    lazy val createEnvelopeResponse: Future[Either[FileUploadError, String]] = Future.successful(Right(someEnvelopedId))

    mockSubmissionRefGenerator.generate shouldReturn SubmissionRef(someSubmissionRef)
    mockFileUploadProxy.createEnvelope(*) shouldReturn createEnvelopeResponse
    mockFileUploadFrontendProxy.upload(*, *, *) shouldReturn Future.successful(Right(()))
    mockFileUploadFrontendProxy.upload(*, *, *, *) shouldReturn Future.successful(Right(()))
    mockFileUploadProxy.routeEnvelope(*) shouldReturn Future.successful(Right(()))

    val submissionService =
      new SubmissionService(mockFileUploadProxy, mockFileUploadFrontendProxy, mockSubmissionRefGenerator)
  }

  "submit" should {

    "create envelope from the given file and config and submit a routing request to DMS" in new TestFixture {

      //when
      submissionService.submit(formsFile, config).unsafeRunSync()

      //then
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("submission-consolidator"),
          Constraints(2, "25MB", "10MB", List("text/plain"), false)
        )
      ) wasCalled once
      mockFileUploadFrontendProxy.upload(someEnvelopedId, FileIds.report, formsFile) wasCalled once
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(MetadataXml.toXml(metaDataDocument))
      ) wasCalled once
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "submission-consolidator", "DMS")
      ) wasCalled once
    }

    "raise error if create envelope fails" in new TestFixture {
      override lazy val createEnvelopeResponse = Future.successful(Left(GenericFileUploadError("some error")))

      //when
      submissionService.submit(formsFile, config).unsafeRunAsync {
        case Left(error) => error shouldBe GenericFileUploadError("some error")
        case Right(_)    => fail("Should have failed")
      }
    }
  }
}
