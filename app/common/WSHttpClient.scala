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

package common

import java.io.File
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{ FileIO, Source }
import org.apache.pekko.util.ByteString
import com.typesafe.config.Config
import play.api.Configuration

import javax.inject.{ Inject, Singleton }
import play.api.libs.json.{ Json, Writes }
import play.api.mvc.MultipartFormData.FilePart
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse, StringContextOps }
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.play.http.logging.Mdc.preservingMdc

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class WSHttpClient @Inject() (
  val config: Configuration,
  val httpClientV2: HttpClientV2
) {

  lazy val configuration: Config = config.underlying
  implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

  def POSTFile(url: String, file: File, contentType: ContentType)(implicit ec: ExecutionContext): Future[HttpResponse] =
    preservingMdc {
      val fileSource: Source[ByteString, NotUsed] =
        FileIO.fromPath(file.toPath).mapMaterializedValue(_ => NotUsed: NotUsed)
      val source: Source[FilePart[Source[ByteString, NotUsed]], NotUsed] = Source(
        FilePart(file.getName, file.getName, Some(contentType.value), fileSource) :: Nil
      )

      httpClientV2
        .post(url"$url")
        .withBody(source)
        .execute[HttpResponse]
    }

  def POSTFile(url: String, fileName: String, body: ByteString, contentType: ContentType)(implicit
    ec: ExecutionContext
  ): Future[HttpResponse] =
    preservingMdc {
      val source: Source[FilePart[Source[ByteString, NotUsed]], NotUsed] = Source(
        FilePart(fileName, fileName, Some(contentType.value), Source.single(body)) :: Nil
      )

      httpClientV2
        .post(url"$url")
        .withBody(source)
        .execute[HttpResponse]
    }

  def POST[I, O](url: String, body: I, headers: Seq[(String, String)] = Seq.empty)(implicit
    wts: Writes[I],
    rds: HttpReads[O],
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[O] =
    httpClientV2
      .post(url"$url")(hc)
      .withBody(Json.toJson(body))
      .setHeader(headers: _*)
      .execute[O]
}
