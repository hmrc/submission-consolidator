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

package consolidator.connectors

import common.WSHttpClient
import consolidator.proxies.{ SdesConfig, SdesNotifyRequest }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.Json
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpReadsInstances, HttpResponse }

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton()
class SdesConnector @Inject() (sdesConfig: SdesConfig, wsHttpClient: WSHttpClient)(implicit
  ex: ExecutionContext
) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def notifySDES(payload: SdesNotifyRequest): Future[Either[Exception, Unit]] = {
    logger.info(s"SDES notification request: ${Json.stringify(Json.toJson(payload))}")
    val url = s"${sdesConfig.baseUrl}${sdesConfig.basePath}/notification/fileready"

    implicit val hc = new HeaderCarrier()

    implicit val legacyRawReads: HttpReads[HttpResponse] =
      HttpReadsInstances.throwOnFailure(HttpReadsInstances.readEitherOf(HttpReadsInstances.readRaw))

    wsHttpClient
      .POST[SdesNotifyRequest, HttpResponse](url, payload, sdesConfig.headers)
      .map { response =>
        if (response.status >= 200 && response.status < 300) {
          Right(())
        } else {
          Left(
            new Exception(s"POST to $url failed with status ${response.status}. Response body: '${response.body}'")
          )
        }
      }
      .recover { case e => Left(new Exception(s"Sdes Notification failed [error=$e]")) }
  }
}
