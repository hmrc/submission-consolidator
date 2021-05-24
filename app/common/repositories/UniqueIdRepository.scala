/*
 * Copyright 2021 HM Revenue & Customs
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
import javax.inject.{ Inject, Singleton }
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{ Index, IndexType }
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class UniqueIdRepository @Inject()(mongoComponent: ReactiveMongoComponent)(implicit ec: ExecutionContext)
    extends ReactiveRepository[UniqueId, BSONObjectID](
      collectionName = "unique_ids",
      mongo = mongoComponent.mongoConnector.db,
      domainFormat = UniqueId.formats,
      idFormat = ReactiveMongoFormats.objectIdFormats
    ) {

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("value" -> IndexType.Ascending),
        name = Some("valueUniqueIdx"),
        unique = true
      )
    )

  def insertWithRetries(idGenFunction: UniqueIdGenFunction, attempts: Int = 5): Future[Option[UniqueId]] =
    (0 until attempts).foldLeft(Future.successful(Option.empty[UniqueId])) {
      (acc: Future[Option[UniqueId]], attempt: Int) =>
        acc.flatMap {
          case None =>
            logger.info(s"insertWithRetries $attempt")
            val uniqueId = idGenFunction()
            insert(uniqueId)
              .map(_ => Option(uniqueId))
              .recoverWith {
                case e =>
                  logger.info("Failed to insert unique id", e)
                  Future.successful(None)
              }
          case other => Future.successful(other)
        }
    }
}

object UniqueIdRepository {

  type UniqueIdGenFunction = () => UniqueId

  case class UniqueId(value: String, id: BSONObjectID = BSONObjectID.generate())

  object UniqueId {
    import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.{ mongoEntity, objectIdFormats }
    implicit val formats = mongoEntity {
      Json.format[UniqueId]
    }
  }
}
