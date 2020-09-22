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
import java.nio.file.{ Files, Paths }
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import akka.util.ByteString
import cats.data.NonEmptyList
import common.UniqueReferenceGenerator.UniqueRef
import common.{ Time, UniqueReferenceGenerator }
import consolidator.proxies.{ Constraints, CreateEnvelopeRequest, FileId, FileUploadError, FileUploadFrontEndProxy, FileUploadProxy, GenericFileUploadError, RouteEnvelopeRequest }
import consolidator.scheduler.ConsolidatorJobParam
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
    lazy val numberOfReportFiles: Int = maxReportAttachments
    private val reportFilesDir: File = {
      val path = Files.createDirectories(
        Paths.get(System.getProperty("java.io.tmpdir") + s"/SubmissionServiceSpec-${System.currentTimeMillis()}")
      )
      (0 until numberOfReportFiles).foreach { i =>
        Files.createFile(Paths.get(path + "/" + s"report-$i.txt")).toFile
      }
      path.toFile
    }
    val reportFiles = reportFilesDir.listFiles().toList
    val config = ConsolidatorJobParam("some-project-id", "some-classification", "some-business-area")
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
    mockFileUploadFrontendProxy.upload(*, *, *) shouldReturn Future.successful(Right(()))
    mockFileUploadFrontendProxy.upload(*, *, *, *) shouldReturn Future.successful(Right(()))
    mockFileUploadProxy.routeEnvelope(*) shouldReturn Future.successful(Right(()))

    val submissionService =
      new SubmissionService(mockFileUploadProxy, mockFileUploadFrontendProxy, mockUniqueReferenceGenerator)
  }

  "submit" should {

    "create a single envelope, when number of reports in less than or equals maxReportAttachments" in new TestFixture {
      //when
      val envelopeIds: NonEmptyList[String] = submissionService.submit(reportFiles, config).unsafeRunSync()

      //then
      envelopeIds shouldEqual NonEmptyList.of(someEnvelopedId)
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("gform"),
          Constraints(numberOfReportFiles + 1, "25MB", "10MB", List("text/plain", "text/csv"), false)
        )
      ) wasCalled once
      mockUniqueReferenceGenerator.generate(12) wasCalled once
      reportFiles.foreach { f =>
        mockFileUploadFrontendProxy
          .upload(someEnvelopedId, FileId(f.getName.split("\\.").head), f) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "text", "text/plain", 24).toXml)
      ) wasCalled once
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "dfs", "DMS")
      ) wasCalled once
    }

    "create multiple envelopes when number of report files exceeds maxReportAttachments" in new TestFixture {
      override lazy val numberOfReportFiles: Int = maxReportAttachments * 2

      //when
      val envelopeIds: NonEmptyList[String] = submissionService.submit(reportFiles, config).unsafeRunSync()

      //then
      envelopeIds shouldEqual NonEmptyList.of(someEnvelopedId, someEnvelopedId)
      mockFileUploadProxy.createEnvelope(
        CreateEnvelopeRequest(
          consolidator.proxies.Metadata("gform"),
          Constraints(maxReportAttachments + 1, "25MB", "10MB", List("text/plain", "text/csv"), false)
        )
      ) wasCalled twice
      reportFiles.foreach { f =>
        mockFileUploadFrontendProxy
          .upload(someEnvelopedId, FileId(f.getName.split("\\.").head), f) wasCalled once
      }
      mockFileUploadFrontendProxy.upload(
        someEnvelopedId,
        FileIds.xmlDocument,
        s"$someSubmissionRef-${DATE_FORMAT.format(now.atZone(ZoneId.systemDefault()))}-metadata.xml",
        ByteString(
          buildMetadataDocument(now.atZone(ZoneId.systemDefault()), "text", "text/plain", maxReportAttachments).toXml)
      ) wasCalled twice
      mockFileUploadProxy.routeEnvelope(
        RouteEnvelopeRequest(someEnvelopedId, "dfs", "DMS")
      ) wasCalled twice
    }

    "raise error if create envelope fails" in new TestFixture {
      override lazy val createEnvelopeResponse = Future.successful(Left(GenericFileUploadError("some error")))

      //when
      submissionService.submit(reportFiles, config).unsafeRunAsync {
        case Left(error) => error shouldBe GenericFileUploadError("some error")
        case Right(_)    => fail("Should have failed")
      }
    }
  }
}
