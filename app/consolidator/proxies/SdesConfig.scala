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

package consolidator.proxies

import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{ Inject, Singleton }

@Singleton
class SdesConfig @Inject() (servicesConfig: ServicesConfig) {

  val baseUrl: String = servicesConfig.baseUrl("sdes")
  val basePath = servicesConfig.getString("microservice.services.sdes.base-path")
  val informationType = servicesConfig.getString("microservice.services.sdes.information-type")
  val recipientOrSender = servicesConfig.getString("microservice.services.sdes.recipient-or-sender")
  val fileLocationUrl = servicesConfig.getString("microservice.services.sdes.file-location-url")
  val zipDirectory = servicesConfig.getString("object-store.zip-directory")

  private val authorizationToken = servicesConfig.getString("microservice.services.sdes.api-key")
  val headers: Seq[(String, String)] = Seq(
    "x-client-id"  -> authorizationToken,
    "Content-Type" -> "application/json"
  )

}
