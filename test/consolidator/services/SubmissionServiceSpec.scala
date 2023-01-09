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

import akka.util.ByteString
import cats.data.NonEmptyList
import cats.effect.IO
import common.UniqueReferenceGenerator.UniqueRef
import common.{ ContentType, Time, UniqueReferenceGenerator }
import consolidator.TestHelper.{ createFileInDir, createTmpDir }
import consolidator.connectors.ObjectStoreConnector
import consolidator.proxies.ObjectStoreConfig
import consolidator.scheduler.{ FileUpload, UntilTime }
import consolidator.services.MetadataDocumentHelper.buildMetadataDocument
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.{ Md5Hash, ObjectSummaryWithMd5 }
import uk.gov.hmrc.objectstore.client.Path.File

import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }
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
      ConsolidationFormat.jsonl,
      FileUpload("some-classification", "some-business-area"),
      UntilTime.now
    )
    val mockObjectStoreConnector = mock[ObjectStoreConnector](withSettings.lenient())
    val mockUniqueReferenceGenerator = mock[UniqueReferenceGenerator](withSettings.lenient())
    val mockSdesService = mock[SdesService](withSettings.lenient())
    val mockFileUploadService = mock[FileUploadService](withSettings.lenient())
    val objectStoreConfig = mock[ObjectStoreConfig](withSettings.lenient())

    val someSubmissionRef = "some-unique-id"
    val now = Instant.now()
    implicit val timeInstant: Time[Instant] = () => now
    implicit val hc = new HeaderCarrier()
    val objectSummary = ObjectSummaryWithMd5(File("test"), 10L, Md5Hash("md5"), Instant.now())

    mockUniqueReferenceGenerator.generate(*) shouldReturn Future.successful(Right(UniqueRef(someSubmissionRef)))
    mockObjectStoreConnector.upload(*, *, *, *[ContentType]) shouldReturn Future.successful(Right(()))
    mockObjectStoreConnector.upload(*, *, *, *[ContentType]) shouldReturn Future.successful(Right(()))
    mockObjectStoreConnector.zipFiles(*) shouldReturn Future.successful(Right(objectSummary))
    mockSdesService.notifySDES(*, *, *[ObjectSummaryWithMd5]) shouldReturn Future.successful(Right(()))
    mockFileUploadService.processReportFiles(*, *, *, *) shouldReturn IO.pure("124")
    objectStoreConfig.enableObjectStore shouldReturn true

    val submissionService =
      new SubmissionService(
        mockUniqueReferenceGenerator,
        mockObjectStoreConnector,
        mockSdesService,
        mockFileUploadService,
        objectStoreConfig
      ) {
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
      envelopeIds should contain
      mockUniqueReferenceGenerator.generate(12) wasCalled once
      reportFiles.foreach { f =>
        mockObjectStoreConnector.upload(
          any[String],
          f.getName.split("\\.").head,
          ByteString(Files.readAllBytes(f.toPath)),
          ContentType.`text/plain`
        ) wasCalled once
      }
      mockObjectStoreConnector.upload(
        any[String],
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "pdf", "application/pdf", 2).toXml),
        ContentType.`application/xml`
      ) wasCalled once
      mockObjectStoreConnector.upload(
        any[String],
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-iform.pdf",
        *,
        ContentType.`application/pdf`
      ) wasCalled once
      mockObjectStoreConnector.zipFiles(any[String]) wasCalled once
      mockSdesService.notifySDES(any[String], any[String], any[ObjectSummaryWithMd5]) wasCalled once
    }

    "create multiple envelopes when size of report file attachments exceeds maxReportAttachments" in new TestFixture {
      override lazy val numberOfReportFiles: Int = 2

      //when
      val envelopeIds: NonEmptyList[String] =
        submissionService.submit(reportFiles, schedulerFormConsolidatorParams).unsafeRunSync()

      //then
      envelopeIds should contain
      reportFiles.foreach { f =>
        mockObjectStoreConnector.upload(
          any[String],
          f.getName.split("\\.").head,
          ByteString(Files.readAllBytes(f.toPath)),
          ContentType.`text/plain`
        ) wasCalled once
      }
      mockObjectStoreConnector.upload(
        any[String],
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "pdf", "application/pdf", 1).toXml),
        ContentType.`application/xml`
      ) wasCalled twice
      mockObjectStoreConnector.upload(
        any[String],
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-iform.pdf",
        *,
        ContentType.`application/pdf`
      ) wasCalled twice
      mockObjectStoreConnector.zipFiles(any[String]) wasCalled twice
      mockSdesService.notifySDES(any[String], any[String], any[ObjectSummaryWithMd5]) wasCalled twice
    }
  }
}
