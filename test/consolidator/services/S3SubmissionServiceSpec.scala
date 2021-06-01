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

package consolidator.services

import cats.data.NonEmptyList
import cats.effect.IO
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, AnonymousAWSCredentials }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import common.Time
import consolidator.TestHelper.{ createFileInDir, createTmpDir }
import consolidator.scheduler.S3
import io.findify.s3mock.S3Mock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId, ZonedDateTime }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source.fromInputStream
import scala.concurrent.duration._

class S3SubmissionServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with ScalaFutures {

  val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
  var s3Mock: S3Mock = _
  var s3Client: AmazonS3 = _
  val bucket = "reports"
  val s3Endpoint = "http://localhost:8001"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    s3Mock = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build()
    s3Mock.start
    s3Client = AmazonS3ClientBuilder
      .standard()
      .withPathStyleAccessEnabled(true)
      .withEndpointConfiguration(new EndpointConfiguration(s3Endpoint, "us-west-2"))
      .withCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()))
      .build()
    s3Client.createBucket(bucket)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    s3Client.shutdown()
    s3Mock.shutdown
  }

  trait TestFixture {
    val zonedNow: ZonedDateTime = Instant.now().atZone(ZoneId.systemDefault())
    implicit val instantTime: Time[Instant] = () => zonedNow.toInstant
    lazy val numberOfReportFiles: Int = 1
    lazy val reportFiles: List[File] = {
      val dir = createTmpDir("S3SubmissionServiceSpec")
      (0 until numberOfReportFiles).foreach(i => createFileInDir(dir, s"report-$i.txt", 10))
      dir.toFile.listFiles().toList
    }
  }

  "submit" should "upload the given files to the given s3 bucket" in new TestFixture {
    override lazy val numberOfReportFiles = 2
    val s3SubmissionService = new S3SubmissionService()
    val io: IO[SubmissionResult] = s3SubmissionService.submit(
      reportFiles,
      S3(new URI(s3Endpoint), bucket)
    )
    val result: SubmissionResult = io.unsafeRunSync()
    result shouldBe S3SubmissionResult(
      NonEmptyList
        .of(s"report-0-${DATE_TIME_FORMAT.format(zonedNow)}.txt", s"report-1-${DATE_TIME_FORMAT.format(zonedNow)}.txt"))
    assertReportFiles(zonedNow, bucket, numberOfReportFiles)
  }

  it should "return error if upload fails" in new TestFixture {
    val s3SubmissionService = new S3SubmissionService()
    val io: IO[SubmissionResult] = s3SubmissionService.submit(
      reportFiles,
      S3(new URI(s3Endpoint), "unknown-bucket")
    )
    whenReady(io.unsafeToFuture().failed, timeout(2.seconds), interval(500.millis)) { e =>
      e shouldBe a[S3UploadException]
      e.getMessage shouldBe "Failed to upload file report-0.txt to s3 bucket unknown-bucket"
    }
  }

  private def assertReportFiles(zonedNow: ZonedDateTime, bucket: String, numberOfFiles: Int) =
    (0 until numberOfFiles).foreach { i =>
      fromInputStream(
        s3Client.getObject(bucket, s"report-$i-${DATE_TIME_FORMAT.format(zonedNow)}.txt").getObjectContent
      ).mkString shouldBe "a" * 10
    }
}
