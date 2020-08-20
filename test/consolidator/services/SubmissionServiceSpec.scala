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

import java.nio.file.{ Files, Path, Paths }
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import akka.util.ByteString
import cats.data.NonEmptyList
import common.Time
import consolidator.proxies.{ Constraints, CreateEnvelopeRequest, FileId, FileUploadError, FileUploadFrontEndProxy, FileUploadProxy, GenericFileUploadError, RouteEnvelopeRequest }
import consolidator.scheduler.ConsolidatorJobParam
import consolidator.services.SubmissionService.{ FileIds, SubmissionRef }
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SubmissionServiceSpec
    extends AnyWordSpec with IdiomaticMockito with ArgumentMatchersSugar with Matchers with FileUploadSettings {

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  trait TestFixture {
    lazy val numberOfReportFiles: Int = maxReportAttachments
    lazy val reportFilesPath: Path = {
      val path = Files.createDirectories(
        Paths.get(System.getProperty("java.io.tmpdir") + s"/SubmissionServiceSpec-${System.currentTimeMillis()}")
      )
      (0 until numberOfReportFiles).foreach { i =>
        Files.createFile(Paths.get(path + "/" + s"report-$i.txt")).toFile
      }
      path
    }
    val config = ConsolidatorJobParam("some-project-id", "some-classification-type", "some-business-area")
    val mockFileUploadProxy = mock[FileUploadProxy](withSettings.lenient())
    val mockFileUploadFrontendProxy = mock[FileUploadFrontEndProxy](withSettings.lenient())
    val mockSubmissionRefGenerator = mock[SubmissionRefGenerator](withSettings.lenient())

    val someEnvelopedId = "some-envelope-id"
    val someSubmissionRef = "SOMESUBMISSIONREF"
    val now = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now

    def metaDataDocument(attachments: Int) = Documents(
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
            Attribute("attachment_count", "int", List(attachments.toString))
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

    "create a single envelope, when number of reports in less than or equals maxReportAttachments" in new TestFixture {

      //when
      val envelopeIds: NonEmptyList[String] = submissionService.submit(reportFilesPath, config).unsafeRunSync()

      //then
      envelopeIds shouldEqual NonEmptyList.of(someEnvelopedId)
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("submission-consolidator"),
          Constraints(numberOfReportFiles + 1, "25MB", "10MB", List("text/plain"), false)
        )
      ) wasCalled once
      reportFilesPath.toFile.listFiles().foreach { f =>
        mockFileUploadFrontendProxy
          .upload(someEnvelopedId, FileId(f.getName.split("\\.").head), f) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(MetadataXml.toXml(metaDataDocument(6)))
      ) wasCalled once
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "submission-consolidator", "DMS")
      ) wasCalled once
    }

    "create multiple envelopes when number of report files exceeds maxReportAttachments" in new TestFixture {
      override lazy val numberOfReportFiles: Int = maxReportAttachments + 1

      //when
      val envelopeIds: NonEmptyList[String] = submissionService.submit(reportFilesPath, config).unsafeRunSync()

      //then
      envelopeIds shouldEqual NonEmptyList.of(someEnvelopedId, someEnvelopedId)
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("submission-consolidator"),
          Constraints(maxReportAttachments + 1, "25MB", "10MB", List("text/plain"), false)
        )
      ) wasCalled once
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("submission-consolidator"),
          Constraints(2, "25MB", "10MB", List("text/plain"), false)
        )
      ) wasCalled once
      reportFilesPath.toFile.listFiles().foreach { f =>
        mockFileUploadFrontendProxy
          .upload(someEnvelopedId, FileId(f.getName.split("\\.").head), f) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(MetadataXml.toXml(metaDataDocument(maxReportAttachments)))
      ) wasCalled once
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(MetadataXml.toXml(metaDataDocument(1)))
      ) wasCalled once
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "submission-consolidator", "DMS")
      ) wasCalled twice
    }

    "raise error if create envelope fails" in new TestFixture {
      override lazy val createEnvelopeResponse = Future.successful(Left(GenericFileUploadError("some error")))

      //when
      submissionService.submit(reportFilesPath, config).unsafeRunAsync {
        case Left(error) => error shouldBe GenericFileUploadError("some error")
        case Right(_)    => fail("Should have failed")
      }
    }
  }
}
