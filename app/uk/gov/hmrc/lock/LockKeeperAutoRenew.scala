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

import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.mongo.lock.MongoLockRepository

import java.util.UUID
import java.util.concurrent.{ Executors, ScheduledExecutorService, TimeUnit }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

trait LockKeeperAutoRenew {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  val repo: MongoLockRepository
  val id: String
  val duration: Duration
  lazy val owner: String = UUID.randomUUID().toString

  def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
    logger.info(s"Trying to acquire lock [id=$id, duration=$duration, owner=$owner]")

    repo
      .takeLock(id, owner, duration)
      .flatMap {
        case Some(_) =>
          val renewalScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
          val period = duration.toMillis - 3000
          if (period > 0) {
            renewalScheduler.scheduleAtFixedRate(
              () => {
                repo.refreshExpiry(id, owner, duration).recover { case e =>
                  logger.warn("Failed to renew lock via renewal renewalTimer", e)
                  false
                }
                ()
              },
              period, // renew 3 seconds before lock timeout
              period,
              TimeUnit.MILLISECONDS
            )
          }

          body.transformWith { bodyResult =>
            Try(renewalScheduler.shutdown()).recover { case e =>
              logger.error("Failed to shutdown renewalScheduler", e)
            }
            repo
              .releaseLock(id, owner)
              .transformWith { releaseLockResult =>
                if (releaseLockResult.isFailure) logger.error("Failed to release lock", releaseLockResult.failed.get)
                bodyResult match {
                  case Success(value) => Future.successful(Some(value))
                  case Failure(error) => Future.failed(error)
                }
              }
          }
        case None =>
          Future.successful(None)
      }
  }

}
