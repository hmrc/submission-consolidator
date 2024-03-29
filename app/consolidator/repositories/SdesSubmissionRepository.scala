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

package consolidator.repositories

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.{ ascending, descending }
import org.mongodb.scala.model.{ IndexModel, IndexOptions }
import org.mongodb.scala.result.DeleteResult
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class SdesSubmissionRepository @Inject() (mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[SdesSubmission](
      mongoComponent = mongo,
      collectionName = "sdes_submission",
      domainFormat = SdesSubmission.format,
      indexes = Seq(
        IndexModel(ascending("status"), IndexOptions().name("statusIdx")),
        IndexModel(descending("createdAt"), IndexOptions().name("createdAtIdx")),
        IndexModel(descending("isProcessed"), IndexOptions().name("isProcessedIdx"))
      )
    ) {

  def upsert(sdesSubmission: SdesSubmission): Future[Either[SdesSubmissionError, Unit]] =
    collection
      .insertOne(sdesSubmission)
      .toFuture()
      .map(_ => Right(()))
      .recover { case e =>
        Left(GenericSdesSubmissionError(e.getMessage))
      }

  def find(id: String): Future[Option[SdesSubmission]] =
    collection.find(equal("_id", id)).headOption()

  def delete(id: String): Future[DeleteResult] =
    collection.deleteOne(equal("_id", id)).toFuture()

  def page(
    selector: Bson,
    orderBy: Bson,
    skip: Int,
    limit: Int
  ): Future[List[SdesSubmission]] =
    collection
      .find(selector)
      .sort(orderBy)
      .skip(skip)
      .limit(limit)
      .toFuture()
      .map(_.toList)

  def count(selector: Bson): Future[Long] =
    collection.countDocuments(selector).toFuture()

}
