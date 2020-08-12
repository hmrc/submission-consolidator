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

import collector.ITSpec
import com.typesafe.config.ConfigFactory
import org.joda.time.{DateTime, DateTimeZone, Duration}
import org.scalatest.time.{Millis, Seconds, Span}
import org.slf4j.{Logger, LoggerFactory}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Configuration}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockFormats.Lock

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LockKeeperAutoRenewSpec
    extends ITSpec {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

  var lockRepository: LockRepository = _

  override def beforeAll() = {
    lockRepository = LockMongoRepository(app.injector.instanceOf[ReactiveMongoComponent].mongoConnector.db)
  }

  override def beforeEach(): Unit =
    lockRepository.removeAll().futureValue

  override def fakeApplication(): Application = {
    val config =
      Configuration(
        ConfigFactory
          .parseString("""
                         | consolidator-jobs = []
                         |""".stripMargin)
          .withFallback(baseConfig.underlying)
      )
    logger.info(s"configuration=$config")
    GuiceApplicationBuilder()
      .configure(config)
      .build()
  }

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
              1
            })
        }
        val future2 = Future {
          otherLockKeeper
            .withLock(Future {
              2
            })
        }

        val future = for {
          future1Result <- future1.flatten
          future2Result <- future2.flatten
        } yield (future1Result, future2Result)

        whenReady(future) { futureResult =>
          futureResult should (
            equal(Some(1), Option.empty) or
              equal(Option.empty, Some(2))
          )
        }
      }
    }

//    "future body takes longer then lock duration" should {
//      "auto renew the lock" in new TestFixture {
//
//        val future = lockKeeper
//          .withLock(Future {
//            val lockBeforeRenew = lockRepository.findAll().futureValue.head
//            sleep(7000) // long running future
//            val lockAfterRenew = lockRepository.findAll().futureValue.head
//            (lockBeforeRenew, lockAfterRenew)
//          })
//        whenReady(future) { locks =>
//          val (lockBeforeRenew, lockAfterRenew) = locks.get
//          lockAfterRenew.expiryTime.isAfter(lockBeforeRenew.expiryTime) shouldBe true
//        }
//      }
//    }
  }
}
