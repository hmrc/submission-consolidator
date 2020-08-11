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
package uk.gov.hmrc.lock

import java.lang.Thread.sleep

import collector.repositories.EmbeddedMongoDBSupport
import org.joda.time.{ DateTime, DateTimeZone, Duration }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import uk.gov.hmrc.lock.LockFormats.Lock
import uk.gov.hmrc.mongo.MongoConnector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LockKeeperAutoRenewSpec
    extends AnyWordSpec with Matchers with EmbeddedMongoDBSupport with ScalaFutures with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override implicit val patienceConfig = PatienceConfig(Span(15, Seconds), Span(1, Millis))

  var lockRepository: LockRepository = _

  override def beforeEach(): Unit =
    lockRepository.removeAll().futureValue

  override def beforeAll(): Unit = {
    initMongoDExecutable()
    startMongoD()
    val mongoDbName: String = "test-" + this.getClass.getSimpleName
    lockRepository = LockMongoRepository(MongoConnector(s"mongodb://$mongoHost:$mongoPort/$mongoDbName").db)
  }

  override def afterAll(): Unit =
    stopMongoD()

  trait TestFixture {
    lazy val lockDuration = Duration.standardSeconds(5)
    lazy val lockKeeper = new LockKeeperAutoRenew {
      override val repo: LockRepository = lockRepository
      override val id: String = "TEST_LOCK"
      override val duration: Duration = lockDuration
    }
  }

  "withLock" when {

    "there is no existing lock" should {
      "acquire the lock and release after future completes" in new TestFixture {
        whenReady(lockKeeper.withLock(Future(1))) { result =>
          result shouldBe Some(1)
          lockRepository.findAll().futureValue shouldBe empty
        }
      }
    }

    "there is no existing lock and duration is less than 3 seconds (auto-renew seconds before duration)" should {
      "acquire the lock and release after future completes" in new TestFixture {
        override lazy val lockDuration: Duration = Duration.standardSeconds(2)
        whenReady(lockKeeper.withLock(Future(1))) { result =>
          result shouldBe Some(1)
          lockRepository.findAll().futureValue shouldBe empty
        }
      }
    }

    "lock acquired and future body fails" should {
      "release the lock and return failure" in new TestFixture {
        whenReady(lockKeeper.withLock(Future.failed(new RuntimeException("future failed"))).failed) { result =>
          result shouldBe a[RuntimeException]
          result.getMessage shouldBe "future failed"
          lockRepository.findAll().futureValue shouldBe empty
        }
      }
    }

    "there is existing lock for a different owner" should {
      "not acquire lock and existing lock is intact" in new TestFixture {
        val now = DateTime.now(DateTimeZone.UTC)
        val existingLock = Lock("TEST_LOCK", "another-owner", now, now.plusSeconds(5))
        lockRepository.insert(existingLock).futureValue

        whenReady(lockKeeper.withLock(Future(1))) { result =>
          result shouldBe None
          lockRepository.findAll().futureValue shouldBe List(existingLock)
        }
      }
    }

    "concurrent access to lock" should {
      "grant the lock to one of the requesters" in new TestFixture {
        val otherLockKeeper = new LockKeeperAutoRenew {
          override val repo: LockRepository = lockRepository
          override val id: String = "TEST_LOCK"
          override val duration: Duration = Duration.standardSeconds(5)
        }
        val future1 = Future {
          lockKeeper
            .withLock(Future {
              sleep(2000)
              1
            })
            .futureValue
        }
        val future2 = Future {
          otherLockKeeper
            .withLock(Future {
              sleep(2000)
              2
            })
            .futureValue
        }
        val future = for {
          future1Result <- future1
          future2Result <- future2
        } yield (future1Result, future2Result)

        whenReady(future) { futureResult =>
          futureResult should (
            equal(Some(1), Option.empty) or
              equal(Option.empty, Some(2))
          )
        }
      }
    }

    "future body takes longer then lock duration" should {
      "auto renew the lock" in new TestFixture {

        val future = lockKeeper
          .withLock(Future {
            val lockBeforeRenew = lockRepository.findAll().futureValue.head
            sleep(7000) // long running future
            val lockAfterRenew = lockRepository.findAll().futureValue.head
            (lockBeforeRenew, lockAfterRenew)
          })
        whenReady(future) { locks =>
          val (lockBeforeRenew, lockAfterRenew) = locks.get
          lockAfterRenew.expiryTime.isAfter(lockBeforeRenew.expiryTime) shouldBe true
        }
      }
    }
  }
}
