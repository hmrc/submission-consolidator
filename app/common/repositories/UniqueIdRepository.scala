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

import common.repositories.UniqueIdRepository.{ UniqueId, UniqueIdGenFunction }
import julienrf.json.derived
import org.bson.types.ObjectId
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{ IndexModel, IndexOptions }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.{ Format, OFormat }
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class UniqueIdRepository @Inject() (mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[UniqueId](
      mongoComponent = mongo,
      collectionName = "unique_ids",
      domainFormat = UniqueId.format,
      indexes = Seq(IndexModel(ascending("value"), IndexOptions().name("valueUniqueIdx").unique(true)))
    ) {
  // This is necessary since repository does not have a TTL index
  override lazy val requiresTtlIndex: Boolean = false

  val logger: Logger = LoggerFactory.getLogger(getClass)

  def insertWithRetries(idGenFunction: UniqueIdGenFunction, attempts: Int = 5): Future[Option[UniqueId]] =
    (0 until attempts).foldLeft(Future.successful(Option.empty[UniqueId])) {
      (acc: Future[Option[UniqueId]], attempt: Int) =>
        acc.flatMap {
          case None =>
            logger.info(s"insertWithRetries $attempt")
            val uniqueId = idGenFunction()
            collection
              .insertOne(uniqueId)
              .toFuture()
              .map(_ => Option(uniqueId))
              .recoverWith { case e =>
                logger.info("Failed to insert unique id", e)
                Future.successful(None)
              }
          case other => Future.successful(other)
        }
    }
}

object UniqueIdRepository {

  type UniqueIdGenFunction = () => UniqueId

  case class UniqueId(value: String, _id: ObjectId = ObjectId.get())

  object UniqueId {
    val format: OFormat[UniqueId] = {
      implicit val oidFormat: Format[ObjectId] = MongoFormats.objectIdFormat
      derived.oformat()
    }
  }
}
