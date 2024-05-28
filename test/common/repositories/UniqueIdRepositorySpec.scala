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

package common.repositories

import common.repositories.UniqueIdRepository.UniqueId
import org.bson.types.ObjectId
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class UniqueIdRepositorySpec
    extends AnyWordSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with ScalaFutures
    with DefaultPlayMongoRepositorySupport[UniqueId] {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))
  override protected val repository: UniqueIdRepository = new UniqueIdRepository(mongoComponent)

  override def beforeEach(): Unit =
    prepareDatabase()

  "insertWithRetries" when {
    "inserted value is unique" should {
      "insert the id into the unqiue_ids collection" in {
        val uniqueId = UniqueId("TEST_VALUE")
        val future = repository.insertWithRetries(() => uniqueId)

        whenReady(future) { result =>
          result shouldBe Some(uniqueId)
          repository.collection.find().toFuture().futureValue shouldBe List(uniqueId)
        }
      }
    }

    "value to be inserted already exists, retries" in {
      val existingUniqueId = UniqueId("TEST_EXISTING_VALUE")
      val newUniqueId = UniqueId("TEST_NEW_VALUE")
      val futureFirstInsert = repository.collection.insertOne(existingUniqueId).toFuture()
      var attempt = 0
      whenReady(futureFirstInsert) { _ =>
        val future = repository.insertWithRetries(
          () =>
            attempt match {
              case 0 =>
                attempt += 1
                existingUniqueId.copy(_id = ObjectId.get())
              case _ =>
                newUniqueId
            },
          2
        )

        whenReady(future) { result =>
          result.map(_.value) shouldBe Some(newUniqueId.value)
          repository.collection.find().toFuture().futureValue.map(_.value) shouldBe List(
            existingUniqueId.value,
            newUniqueId.value
          )
        }
      }
    }
  }
}
