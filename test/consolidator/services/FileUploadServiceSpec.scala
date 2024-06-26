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

package consolidator.services

import org.apache.pekko.util.ByteString
import common.UniqueReferenceGenerator.UniqueRef
import common.{ ContentType, Time, UniqueReferenceGenerator }
import consolidator.TestHelper.{ createFileInDir, createTmpDir }
import consolidator.proxies._
import consolidator.scheduler.{ FileUpload, UntilTime }
import consolidator.services.FileUploadService.FileIds
import consolidator.services.MetadataDocumentHelper.buildMetadataDocument
import org.mockito.ArgumentMatchersSugar
import org.mockito.quality.Strictness
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileUploadServiceSpec
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
      ConsolidationFormat.jsonl,
      FileUpload("some-classification", "some-business-area"),
      UntilTime.now
    )
    val mockFileUploadProxy = mock[FileUploadProxy](withSettings.strictness(Strictness.LENIENT))
    val mockFileUploadFrontendProxy = mock[FileUploadFrontEndProxy](withSettings.strictness(Strictness.LENIENT))
    val mockUniqueReferenceGenerator = mock[UniqueReferenceGenerator](withSettings.strictness(Strictness.LENIENT))

    val expectedEnvelopedId = "some-envelope-id"
    val someSubmissionRef = "some-unique-id"
    val now = Instant.now()
    val zonedDateTime = now.atZone(ZoneId.systemDefault())
    implicit val timeInstant: Time[Instant] = () => now

    lazy val createEnvelopeResponse: Future[Either[FileUploadError, String]] =
      Future.successful(Right(expectedEnvelopedId))

    mockUniqueReferenceGenerator.generate(*) shouldReturn Future.successful(Right(UniqueRef(someSubmissionRef)))
    mockFileUploadProxy.createEnvelope(*) shouldReturn createEnvelopeResponse
    mockFileUploadFrontendProxy.upload(*, *, *, *[ContentType]) shouldReturn Future.successful(Right(()))
    mockFileUploadFrontendProxy.upload(*, *, *, *, *[ContentType]) shouldReturn Future.successful(Right(()))
    mockFileUploadProxy.routeEnvelope(*) shouldReturn Future.successful(Right(()))

    val fileUploadService =
      new FileUploadService(mockFileUploadProxy, mockFileUploadFrontendProxy, mockUniqueReferenceGenerator) {
        override lazy val maxReportAttachmentsSize = maxReportAttachmentsSizeOverride
      }
  }

  "processReportFiles" should {

    "create a single envelope, when size of report files is less than or equals maxReportAttachmentsSize" in new TestFixture {
      override lazy val maxReportAttachmentsSizeOverride: Long = 20
      override lazy val numberOfReportFiles: Int = 2
      //when
      val envelopeId: String =
        fileUploadService
          .processReportFiles(reportFiles, schedulerFormConsolidatorParams, 12, zonedDateTime)
          .unsafeRunSync()

      //then
      envelopeId shouldEqual expectedEnvelopedId
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
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            ),
            false
          )
        )
      ) wasCalled once
      mockUniqueReferenceGenerator.generate(12) wasCalled once
      reportFiles.foreach { f =>
        mockFileUploadFrontendProxy
          .upload(expectedEnvelopedId, FileId(f.getName.split("\\.").head), f, ContentType.`text/plain`) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        expectedEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "pdf", "application/pdf", 2).toXml),
        ContentType.`application/xml`
      ) wasCalled once
      mockFileUploadFrontendProxy.upload(
        expectedEnvelopedId,
        FileIds.pdf,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-iform.pdf",
        *,
        ContentType.`application/pdf`
      ) wasCalled once
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(expectedEnvelopedId, "dfs", "DMS")
      ) wasCalled once
    }

    "raise error if create envelope fails" in new TestFixture {
      override lazy val createEnvelopeResponse = Future.successful(Left(GenericFileUploadError("some error")))

      //when
      fileUploadService
        .processReportFiles(reportFiles, schedulerFormConsolidatorParams, 12, zonedDateTime)
        .unsafeRunAsync {
          case Left(error) => error shouldBe GenericFileUploadError("some error")
          case Right(_)    => fail("Should have failed")
        }
    }
  }
}
