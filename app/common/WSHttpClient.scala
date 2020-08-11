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

package common

import java.io.File

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{ FileIO, Source }
import akka.util.ByteString
import com.typesafe.config.Config
import javax.inject.{ Inject, Singleton }
import play.api.libs.ws.WSClient
import play.api.mvc.MultipartFormData.FilePart
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{ HeaderCarrier, HttpPost, HttpResponse }
import uk.gov.hmrc.play.http.logging.Mdc.preservingMdc
import uk.gov.hmrc.play.http.ws.{ WSHttpResponse, WSPost }

import scala.concurrent.{ ExecutionContext, Future }
@Singleton
class WSHttpClient @Inject()(override val wsClient: WSClient, override val actorSystem: ActorSystem)
    extends HttpPost with WSPost {
  override def applicableHeaders(url: String)(implicit hc: HeaderCarrier): Seq[(String, String)] = hc.headers
  override protected def configuration: Option[Config] = None
  override val hooks: Seq[HttpHook] = Seq.empty
  implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

  def POSTFile(url: String, file: File)(implicit ec: ExecutionContext): Future[HttpResponse] =
    preservingMdc {
      buildRequest(url)
        .post(Source(FilePart(file.getName, file.getName, None, FileIO.fromPath(file.toPath)) :: Nil))
        .map(WSHttpResponse(_))
    }

  def POSTFile(url: String, fileName: String, body: ByteString)(implicit ec: ExecutionContext): Future[HttpResponse] =
    preservingMdc {
      val source: Source[FilePart[Source[ByteString, NotUsed]], NotUsed] = Source(
        FilePart(fileName, fileName, None, Source.single(body)) :: Nil
      )
      buildRequest(url).post(source).map(WSHttpResponse(_))
    }
}
