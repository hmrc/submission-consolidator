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

package collector.repositories

import consolidator.repositories.FormsMetadata
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.Json.{ obj, toJson }
import play.api.libs.json.{ JsNumber, JsObject, JsString, Json }
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor.FailOnError
import reactivemongo.api.QueryOpts
import reactivemongo.api.commands.LastError
import reactivemongo.api.indexes.{ Index, IndexType }
import reactivemongo.bson.BSONObjectID
import reactivemongo.core.actors.Exceptions.PrimaryUnavailableException
import reactivemongo.play.json.ImplicitBSONHandlers
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FormRepository @Inject()(mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[Form, BSONObjectID](
      collectionName = "forms",
      mongo = mongoComponent.mongoConnector.db,
      domainFormat = Form.formats,
      idFormat = ReactiveMongoFormats.objectIdFormats
    ) {
  import ImplicitBSONHandlers._

  override val logger: Logger = LoggerFactory.getLogger(getClass)

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("submissionRef" -> IndexType.Ascending),
        name = Some("submissionRefUniqueIdx"),
        unique = true
      ),
      Index(
        Seq(
          "projectId" -> IndexType.Ascending
        ),
        name = Some("projectIdIdx")
      ),
      Index(
        Seq("submissionTimestamp" -> IndexType.Ascending),
        name = Some("submissionTimestampIdx")
      )
    )

  def addForm(
    form: Form
  )(implicit ec: ExecutionContext): Future[Either[FormError, Unit]] =
    insert(form)
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
        case unavailable: PrimaryUnavailableException =>
          logger.error("Mongodb is unavailable", unavailable)
          Left(MongoUnavailable(unavailable.getMessage))
        case other =>
          logger.error("Mongodb error", other)
          Left(MongoGenericError(other.getMessage))
      }

  /**
    * Gets all the forms for the given project id. batchSize is used to limit the data returned.
    * To read the next batch, pass the last object id of the previous batch, in the afterObjectId (exclusive) parameter.
    * untilObjectId can be used to set an upperbound (inclusive) for the query.
    * @param projectId The project id to get forms for
    * @param batchSize The size of the batch, to limit documents fetched
    * @param afterObjectId Optional last object id of the previous batch (exclusive)
    * @param untilObjectId Optional upperbound object id (inclusive)
    * @param ec The execution context to run Futures in
    * @return
    */
  def getForms(
    projectId: String,
    batchSize: Int
  )(afterObjectId: Option[BSONObjectID] = None, untilObjectId: Option[BSONObjectID] = None)(
    implicit
    ec: ExecutionContext): Future[Either[FormError, List[Form]]] = {

    val selector = JsObject(
      Seq(
        "projectId" -> toJson(projectId)
      ) ++
        ((afterObjectId, untilObjectId) match {
          case (None, None)       => None
          case (Some(aoid), None) => Some("_id" -> obj("$gt" -> ReactiveMongoFormats.objectIdWrite.writes(aoid)))
          case (None, Some(uoid)) => Some("_id" -> obj("$lte" -> ReactiveMongoFormats.objectIdWrite.writes(uoid)))
          case (Some(aoid), Some(uoid)) =>
            Some(
              "_id" -> obj(
                "$gt"  -> ReactiveMongoFormats.objectIdWrite.writes(aoid),
                "$lte" -> ReactiveMongoFormats.objectIdWrite.writes(uoid)
              )
            )
        })
    )

    collection
      .find(selector, Option.empty[JsObject])
      .sort(Json.obj("_id" -> 1))
      .options(QueryOpts().batchSize(batchSize))
      .cursor[Form]()
      .collect[List](batchSize, FailOnError[List[Form]]())
      .map(Right(_))
      .recover {
        case e =>
          logger.error("Mongodb error", e)
          Left(MongoGenericError(e.getMessage))
      }
  }

  /**
    * Get count of forms and the max id, optionally after the given afterObjectId
    *
    * db.forms.aggregate([
    * {
    *    $match: {
    *     projectId: "test-project"
    *    }
    * },
    * {
    *    $group: {
    *        _id: null,
    *          count: {
    *          $sum: 1
    *        },
    *        maxId: {
    *          $max: "$_id"
    *        }
    *    }
    * }])
    *
    * @param projectId The project id to ge forms metadata for
    * @param afterObjectId Optional - gets form metadata after the given object id
    * @param ec The execution context for Futures
    * @return Either an error or an optional FormsMetadata (in case of no forms, this will be None)
    */
  def getFormsMetadata(
    projectId: String,
    afterObjectId: Option[BSONObjectID] = None
  )(implicit ec: ExecutionContext): Future[Either[FormError, Option[FormsMetadata]]] = {
    import collection.BatchCommands.AggregationFramework._

    val matchStage = Match(
      Json.obj("projectId" -> projectId) ++ afterObjectId
        .map { aoid =>
          obj("_id" -> obj("$gt" -> ReactiveMongoFormats.objectIdWrite.writes(aoid)))
        }
        .getOrElse(obj())
    )

    val groupStage = Group(Json.obj())(
      "count" -> Sum(JsNumber(1)),
      "maxId" -> Max(JsString("$_id"))
    )

    collection
      .aggregateWith[FormsMetadata]()(_ => matchStage -> List(groupStage))
      .headOption
      .map(Right(_))
      .recover {
        case e =>
          logger.error("Mongodb error", e)
          Left(MongoGenericError(e.getMessage))
      }
  }

  object MongoError {
    def unapply(lastError: LastError): Option[(Option[Int], String)] = Some((lastError.code, lastError.message))
  }
}
