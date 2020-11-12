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

import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import akka.util.ByteString
import cats.data.NonEmptyList
import common.UniqueReferenceGenerator.UniqueRef
import common.{ ContentType, Time, UniqueReferenceGenerator }
import consolidator.TestHelper.{ createFileInDir, createTmpDir }
import consolidator.proxies._
import consolidator.scheduler.UntilTime
import consolidator.services.MetadataDocumentHelper.buildMetadataDocument
import consolidator.services.SubmissionService.FileIds
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SubmissionServiceSpec
    extends AnyWordSpec with IdiomaticMockito with ArgumentMatchersSugar with Matchers with FileUploadSettings {

  private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

  trait TestFixture {
    lazy val numberOfReportFiles: Int = 1
    lazy val maxReportAttachmentsSizeOverride: Long = 10
    val reportFiles = {
      val dir = createTmpDir("SubmissionServiceSpec")
      (0 until numberOfReportFiles).foreach(i => createFileInDir(dir, s"report-$i.txt", 10))
      dir.toFile.listFiles().toList
    }
    val projectId = "some-project-id"
    val schedulerFormConsolidatorParams = ScheduledFormConsolidatorParams(
      projectId,
      "some-classification",
      "some-business-area",
      ConsolidationFormat.jsonl,
      UntilTime.now)
    val mockFileUploadProxy = mock[FileUploadProxy](withSettings.lenient())
    val mockFileUploadFrontendProxy = mock[FileUploadFrontEndProxy](withSettings.lenient())
    val mockUniqueReferenceGenerator = mock[UniqueReferenceGenerator](withSettings.lenient())

    val someEnvelopedId = "some-envelope-id"
    val someSubmissionRef = "some-unique-id"
    val now = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now

    lazy val createEnvelopeResponse: Future[Either[FileUploadError, String]] = Future.successful(Right(someEnvelopedId))

    mockUniqueReferenceGenerator.generate(*) shouldReturn Future.successful(Right(UniqueRef(someSubmissionRef)))
    mockFileUploadProxy.createEnvelope(*) shouldReturn createEnvelopeResponse
    mockFileUploadFrontendProxy.upload(*, *, *, *[ContentType]) shouldReturn Future.successful(Right(()))
    mockFileUploadFrontendProxy.upload(*, *, *, *, *[ContentType]) shouldReturn Future.successful(Right(()))
    mockFileUploadProxy.routeEnvelope(*) shouldReturn Future.successful(Right(()))

    val submissionService =
      new SubmissionService(mockFileUploadProxy, mockFileUploadFrontendProxy, mockUniqueReferenceGenerator) {
        override lazy val maxReportAttachmentsSize = maxReportAttachmentsSizeOverride
      }
  }

  "submit" should {

    "create a single envelope, when size of report files is less than or equals maxReportAttachmentsSize" in new TestFixture {
      override lazy val maxReportAttachmentsSizeOverride: Long = 20
      override lazy val numberOfReportFiles: Int = 2
      //when
      val envelopeIds: NonEmptyList[String] =
        submissionService.submit(reportFiles, schedulerFormConsolidatorParams).unsafeRunSync()

      //then
      envelopeIds shouldEqual NonEmptyList.of(someEnvelopedId)
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("gform"),
          Constraints(
            numberOfReportFiles + 2,
            "25MB",
            "10MB",
            List(
              "text/plain",
              "text/csv",
              "application/pdf",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            false
          )
        )
      ) wasCalled once
      mockUniqueReferenceGenerator.generate(12) wasCalled once
      reportFiles.foreach { f =>
        mockFileUploadFrontendProxy
          .upload(someEnvelopedId, FileId(f.getName.split("\\.").head), f, ContentType.`text/plain`) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "pdf", "application/pdf", 2).toXml),
        ContentType.`application/xml`
      ) wasCalled once
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.pdf,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-iform.pdf",
        *,
        ContentType.`application/pdf`
      ) wasCalled once
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "dfs", "DMS")
      ) wasCalled once
    }

    "create multiple envelopes when size of report file attachments exceeds maxReportAttachments" in new TestFixture {
      override lazy val numberOfReportFiles: Int = 2

      //when
      val envelopeIds: NonEmptyList[String] =
        submissionService.submit(reportFiles, schedulerFormConsolidatorParams).unsafeRunSync()

      //then
      envelopeIds shouldEqual NonEmptyList.of(someEnvelopedId, someEnvelopedId)
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("gform"),
          Constraints(
            3,
            "25MB",
            "10MB",
            List(
              "text/plain",
              "text/csv",
              "application/pdf",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            false)
        )
      ) wasCalled twice
      reportFiles.foreach { f =>
        mockFileUploadFrontendProxy
          .upload(someEnvelopedId, FileId(f.getName.split("\\.").head), f, ContentType.`text/plain`) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "pdf", "application/pdf", 1).toXml),
        ContentType.`application/xml`
      ) wasCalled twice
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.pdf,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-iform.pdf",
        *,
        ContentType.`application/pdf`
      ) wasCalled twice
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "dfs", "DMS")
      ) wasCalled twice
    }

    "raise error if create envelope fails" in new TestFixture {
      override lazy val createEnvelopeResponse = Future.successful(Left(GenericFileUploadError("some error")))

      //when
      submissionService.submit(reportFiles, schedulerFormConsolidatorParams).unsafeRunAsync {
        case Left(error) => error shouldBe GenericFileUploadError("some error")
        case Right(_)    => fail("Should have failed")
      }
    }
  }
}
