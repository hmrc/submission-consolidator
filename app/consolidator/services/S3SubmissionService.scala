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

import cats.effect.{ ContextShift, IO }
import common.Time
import consolidator.IOUtils
import consolidator.scheduler.S3
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.PutObjectRequest

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }
import javax.inject.{ Inject, Singleton }
import scala.compat.java8.FutureConverters.toScala
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class S3SubmissionService @Inject()(implicit ec: ExecutionContext) extends IOUtils {

  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  def submit(reportFiles: List[File], s3Destination: S3)(
    implicit
    time: Time[Instant]): IO[SubmissionResult] = {

    implicit val now: Instant = time.now()
    val zonedDateTime = now.atZone(ZoneId.systemDefault())

    val s3: S3AsyncClient = S3AsyncClient.builder.endpointOverride(s3Destination.s3Endpoint).build()
    IO.fromFuture(
      IO(
        Future
          .traverse(reportFiles) { file =>
            toScala(
              s3.putObject(
                PutObjectRequest
                  .builder()
                  .bucket(s3Destination.bucket)
                  .key({
                    val extIndex = file.getName.lastIndexOf(".")
                    file.getName.substring(0, extIndex) + "-" + DATE_TIME_FORMAT.format(zonedDateTime) + file.getName
                      .substring(extIndex)
                  })
                  .build(),
                file.toPath
              )
            ).recoverWith {
              case e =>
                Future.failed(
                  S3UploadException(s"Failed to upload file ${file.getName} to s3 bucket ${s3Destination.bucket}", e))
            }
          }
          .map(_ => S3SubmissionResult)
      )
    )
  }
}
