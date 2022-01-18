/*
 * Copyright 2022 HM Revenue & Customs
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

package collector.repositories

import org.slf4j.{ Logger, LoggerFactory }
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object IndexManager {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  case class IndexDef(name: String, fields: Seq[String], unique: Boolean, ttl: Option[Long])

  def indexInfo(collection: JSONCollection): Future[Seq[IndexDef]] =
    collection.indexesManager.list().map { indexes =>
      indexes.map { index =>
        val ttl = index.options.getAs[Long]("expireAfterSeconds")
        IndexDef(
          index.eventualName,
          index.key.map(_._1),
          index.unique,
          ttl
        )
      }
    }

  def checkIndexTtl(collection: JSONCollection, indexName: String, ttl: Option[Long]): Future[Unit] =
    indexInfo(collection)
      .flatMap { seqIndexes =>
        seqIndexes
          .find(index => index.name == indexName && index.ttl != ttl)
          .map { index =>
            logger.warn(
              s"Index $indexName on collection ${collection.name} with TTL ${index.ttl} does not match configuration value $ttl"
            )
            collection.indexesManager.drop(index.name) map {
              case n if n > 0 =>
                logger.warn(s"Dropped index $indexName on collection ${collection.name} as TTL value incorrect")
              case _ =>
                logger.warn(
                  s"Index index $indexName on collection ${collection.name} had already been dropped (possible race condition)"
                )
            }
          } getOrElse Future.successful(
          logger.info(s"Index $indexName on collection ${collection.name} has correct TTL $ttl")
        )
      }
}
