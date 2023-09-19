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

package collector.repositories

import akka.stream.scaladsl.Source
import org.bson.types.ObjectId
import org.mongodb.scala.bson.BsonValue
import org.mongodb.scala.model.Aggregates.{ group, sort, unwind }
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{ Aggregates, IndexModel, IndexOptions, Sorts }
import org.mongodb.scala.{ MongoSocketWriteException, MongoWriteException }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.Configuration
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{ Codecs, PlayMongoRepository }

import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FormRepository @Inject() (mongo: MongoComponent, config: Configuration)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[Form](
      mongoComponent = mongo,
      collectionName = "forms",
      domainFormat = Form.format,
      indexes = Seq(
        IndexModel(ascending("submissionRef"), IndexOptions().name("submissionRefUniqueIdx").unique(true)),
        IndexModel(ascending("projectId"), IndexOptions().name("projectIdIdx")),
        IndexModel(
          ascending("submissionTimestamp"),
          IndexOptions()
            .name("submissionTimestampIdx")
            .background(false)
            .expireAfter(config.get[Long]("mongodb.timeToLiveInSeconds"), TimeUnit.SECONDS)
        )
      ),
      replaceIndexes = true
    ) {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  def addForm(
    form: Form
  )(implicit ec: ExecutionContext): Future[Either[FormError, Unit]] =
    collection
      .insertOne(form)
      .toFuture()
      .map(_ => Right(()))
      .recover {
        case MongoError(Some(11000), message) if message.contains("submissionRef") =>
          logger.error(s"Duplicate submissionRef found ${form.submissionRef}")
          Left(
            DuplicateSubmissionRef(
              form.submissionRef,
              "submissionRef must be unique"
            )
          )
        case unavailable: MongoSocketWriteException =>
          logger.error("Mongodb is unavailable", unavailable)
          Left(MongoUnavailable(unavailable.getMessage))
        case other =>
          logger.error("Mongodb error", other)
          Left(MongoGenericError(other.getMessage))
      }

  /** @param projectId
    * @param afterObjectId
    * @param ec
    * @return
    */
  def distinctFormDataIds(projectId: String, afterObjectId: Option[ObjectId] = None)(implicit
    ec: ExecutionContext
  ): Future[Either[FormError, List[String]]] = {

    val filter = Aggregates.filter(
      and(equal("projectId", projectId), afterObjectId.map(aoid => gt("_id", aoid)).getOrElse(exists("_id")))
    )

    collection
      .aggregate[BsonValue](
        List(
          filter,
          unwind("$formData"),
          group("$formData.id"),
          sort(Sorts.ascending("_id"))
        )
      )
      .toFuture()
      .map(_.map(Codecs.fromBson[AggregateResult](_)._id).toList)
      .map(Right(_))
      .recover { case e =>
        Left(MongoGenericError(e.getMessage))
      }
  }

  def formsSource(
    projectId: String,
    batchSize: Int,
    afterObjectId: Option[ObjectId],
    untilInstant: Instant // inclusive
  ): Source[Form, Future[Unit]] = {

    logger.info(
      s"Fetching forms from ${afterObjectId.map(aoid => aoid.getTimestamp)}(exclusive) until $untilInstant(inclusive) for project $projectId"
    )

    val filter = and(
      equal("projectId", projectId),
      lte("_id", ObjectId.getSmallestWithDate(Date.from(untilInstant))),
      afterObjectId.map(aoid => gt("_id", aoid)).getOrElse(exists("_id"))
    )

    Source
      .fromPublisher(
        collection
          .find[BsonValue](filter)
          .batchSize(batchSize)
      )
      .map(Codecs.fromBson[Form](_))
      .mapMaterializedValue(_ => Future.successful(()))
  }

  object MongoError {
    def unapply(lastError: MongoWriteException): Option[(Option[Int], String)] = Some(
      (Some(lastError.getCode), lastError.getMessage)
    )
  }
}
