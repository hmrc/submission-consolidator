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

import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import akka.util.ByteString
import common.ContentType
import consolidator.proxies.SdesConfig
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.test.Helpers
import uk.gov.hmrc.objectstore.client.RetentionPeriod.OneWeek
import uk.gov.hmrc.objectstore.client.config.ObjectStoreClientConfig
import uk.gov.hmrc.objectstore.client.play.test.stub

import java.util.UUID.randomUUID
import scala.concurrent.ExecutionContext.Implicits.global

class ObjectStoreConnectorSpec
    extends TestKit(ActorSystem("ObjectStoreConnectorSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll
    with IdiomaticMockito with ArgumentMatchersSugar with ImplicitSender with ScalaFutures {
  trait TestFixture {

    implicit val system = ActorSystem()
    val baseUrl = s"baseUrl-${randomUUID().toString}"
    val owner = s"owner-${randomUUID().toString}"
    val token = s"token-${randomUUID().toString}"
    val config = ObjectStoreClientConfig(baseUrl, owner, token, OneWeek)

    val objectStoreStub = new stub.StubPlayObjectStoreClient(config)
    val mockSdesConfig = mock[SdesConfig](withSettings.lenient())

    val objectStoreConnector =
      new ObjectStoreConnector(objectStoreStub, Helpers.stubControllerComponents(), mockSdesConfig)
  }

  "upload" should {
    "update a file" in new TestFixture {

      val response =
        objectStoreConnector.upload("envelope-id", "fileName", ByteString("test"), ContentType.`application/pdf`)

      response.futureValue shouldBe Right(())
    }
  }

  "deleteZipFile" should {
    "delete zipped file" in new TestFixture {

      val response = objectStoreConnector.deleteZipFile("envelope-id")

      response.futureValue shouldBe (())
    }
  }

}
