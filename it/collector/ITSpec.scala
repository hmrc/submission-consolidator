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
package collector

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.{BaseOneServerPerSuite, FakeApplicationFactory}
import play.api.libs.ws.WSClient

trait ITSpec
    extends AnyWordSpecLike with BaseOneServerPerSuite with FakeApplicationFactory with Matchers with BeforeAndAfterAll
    with BeforeAndAfterEach with ScalaFutures {

  private val mongoDbName: String = "test-" + this.getClass.getSimpleName
  lazy val configurationOverrides: Map[String, Any] =
    Map("mongodb.uri" -> s"mongodb://localhost:27017/$mongoDbName", "auditing.enabled" -> false)

  lazy val baseUrl: String =
    s"http://localhost:$port/submission-consolidator/form"
  lazy val wsClient = app.injector.instanceOf[WSClient]
}
