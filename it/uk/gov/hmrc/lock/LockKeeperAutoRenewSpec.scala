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

package uk.gov.hmrc.lock

import collector.ITSpec
import com.typesafe.config.ConfigFactory
import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest.time.{Millis, Seconds, Span}
import org.slf4j.{Logger, LoggerFactory}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Configuration}
import uk.gov.hmrc.mongo.lock.{Lock, MongoLockRepository}
import uk.gov.hmrc.mongo.{CurrentTimestampSupport, MongoComponent}

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

class LockKeeperAutoRenewSpec
    extends ITSpec {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

  var lockRepository: MongoLockRepository = _

  override def beforeAll() = {
    lockRepository = new MongoLockRepository(app.injector.instanceOf[MongoComponent],new CurrentTimestampSupport())
  }

  override def beforeEach(): Unit =
    lockRepository.collection.deleteMany(Document()).toFuture()

  override def fakeApplication(): Application = {
    val config =
      Configuration(
        ConfigFactory
          .parseString("""
                         | consolidator-jobs = []
                         |""".stripMargin)
          .withFallback(baseConfig.underlying)
      )
    GuiceApplicationBuilder()
      .configure(config)
      .build()
  }

  trait TestFixture {
    lazy val lockDuration = Duration.create(3,TimeUnit.SECONDS)
    lazy val lockKeeper =new LockKeeperAutoRenew {
      override val repo: MongoLockRepository = lockRepository
      override val id: String = "TEST_LOCK"
      override val duration: Duration = lockDuration
    }
  }

  "withLock" when {

    "there is no existing lock" should {
      "acquire the lock and release after future completes" in new TestFixture {
        val future = lockKeeper.withLock(Future(1)).futureValue
        lockRepository.isLocked(lockKeeper.id,lockKeeper.owner).futureValue shouldEqual false
        future shouldBe Some(1)
      }
    }

    "there is no existing lock and duration is less than 3 seconds (auto-renew seconds before duration)" should {
      "acquire the lock and release after future completes" in new TestFixture {
        override lazy val lockDuration = Duration.create(2,TimeUnit.SECONDS)
        val future = lockKeeper.withLock(Future(1))
        Thread.sleep(2000)
        lockRepository.isLocked(lockKeeper.id,lockKeeper.owner).futureValue shouldEqual false
        whenReady(future) { result =>
          result shouldBe Some(1)
        }
      }
    }

    "lock acquired and future body fails" should {
      "release the lock and return failure" in new TestFixture {
        whenReady(lockKeeper.withLock(Future.failed(new RuntimeException("future failed"))).failed) { result =>
          result shouldBe a[RuntimeException]
          result.getMessage shouldBe "future failed"
        }
      }
    }

    "there is existing lock for a different owner" should {
      "not acquire lock and existing lock is intact" in new TestFixture {
        val now = Instant.now
        val existingLock = Lock("TEST_LOCK", "another-owner", now, now.plusSeconds(5))
        lockRepository.takeLock(existingLock.id,existingLock.owner,Duration.create(existingLock.expiryTime.toEpochMilli - existingLock.timeCreated.toEpochMilli,TimeUnit.MILLISECONDS)).futureValue

        whenReady(lockKeeper.withLock(Future(1))) { result =>
          result shouldBe None
          lockRepository.collection.find().toFuture().futureValue shouldEqual List(existingLock)
        }
      }
    }

    "concurrent access to lock" should {
      "grant the lock to one of the requesters" in new TestFixture {
        val otherLockKeeper = new LockKeeperAutoRenew {
          override val repo: MongoLockRepository = lockRepository
          override val id: String = "TEST_LOCK"
          override val duration: Duration = Duration.create(5,TimeUnit.SECONDS)
        }
        val future1 = Future {
          lockKeeper
            .withLock(Future {
              Thread.sleep(1000)
              1
            })
        }
        val future2 = Future {
          otherLockKeeper
            .withLock(Future {
              Thread.sleep(1000)
              2
            })
        }

        val future = for {
          future1Result <- future1.flatten
          future2Result <- future2.flatten
        } yield (future1Result, future2Result)

        whenReady(future) { futureResult =>
          futureResult should (
            equal((Some(1), Option.empty)) or
              equal((Option.empty, Some(2)))
            )
        }
      }
    }
  }
}
