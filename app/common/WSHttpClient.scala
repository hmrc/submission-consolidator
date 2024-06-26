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
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ FileIO, Source }
import org.apache.pekko.util.ByteString
import com.typesafe.config.Config
import play.api.Configuration

import javax.inject.{ Inject, Singleton }
import play.api.libs.ws.WSClient
import play.api.mvc.MultipartFormData.FilePart
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{ HeaderCarrier, HttpPost, HttpResponse }
import uk.gov.hmrc.play.http.logging.Mdc.preservingMdc
import uk.gov.hmrc.play.http.ws.{ WSHttpResponse, WSPost }

import scala.concurrent.{ ExecutionContext, Future }
@Singleton
class WSHttpClient @Inject() (
  val config: Configuration,
  override val wsClient: WSClient,
  override val actorSystem: ActorSystem
) extends HttpPost with WSPost {

  override lazy val configuration: Config = config.underlying
  override val hooks: Seq[HttpHook] = Seq.empty
  implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

  def POSTFile(url: String, file: File, contentType: ContentType)(implicit ec: ExecutionContext): Future[HttpResponse] =
    preservingMdc {
      buildRequest(url, Seq.empty)
        .post(
          Source(FilePart(file.getName, file.getName, Some(contentType.value), FileIO.fromPath(file.toPath)) :: Nil)
        )
        .map(WSHttpResponse(_))
    }

  def POSTFile(url: String, fileName: String, body: ByteString, contentType: ContentType)(implicit
    ec: ExecutionContext
  ): Future[HttpResponse] =
    preservingMdc {
      val source: Source[FilePart[Source[ByteString, NotUsed]], NotUsed] = Source(
        FilePart(fileName, fileName, Some(contentType.value), Source.single(body)) :: Nil
      )
      buildRequest(url, Seq.empty).post(source).map(WSHttpResponse(_))
    }
}
