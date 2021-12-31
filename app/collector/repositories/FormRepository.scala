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

package collector.repositories

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import cats.syntax.either._
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.Json.{ obj, toJson }
import play.api.libs.json.{ JsObject, JsString, Json }
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.akkastream.cursorProducer
import reactivemongo.api.commands.LastError
import reactivemongo.api.indexes.{ Index, IndexType }
import reactivemongo.api.{ Cursor, QueryOpts }
import reactivemongo.bson.{ BSONDateTime, BSONObjectID }
import reactivemongo.core.actors.Exceptions.PrimaryUnavailableException
import reactivemongo.play.json.ImplicitBSONHandlers
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FormRepository @Inject()(mongoComponent: ReactiveMongoComponent)(implicit system: ActorSystem)
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
    * @param projectId
    * @param afterObjectId
    * @param ec
    * @return
    */
  def distinctFormDataIds(projectId: String, afterObjectId: Option[BSONObjectID] = None)(
    implicit
    ec: ExecutionContext): Future[Either[FormError, List[String]]] =
    collection
      .aggregateWith[JsObject]() { framework =>
        import framework._
        val afterObjectIdSelector: JsObject = afterObjectId
          .map(aoid => obj("_id" -> obj("$gt" -> ReactiveMongoFormats.objectIdWrite.writes(aoid))))
          .getOrElse(obj())
        Match(Json.obj("projectId" -> JsString(projectId)) ++ afterObjectIdSelector) -> List(
          UnwindField("formData"),
          GroupField("formData.id")(),
          Sort(Ascending("_id"))
        )
      }
      .collect[List](-1, Cursor.FailOnError())
      .map(jsObjectList => jsObjectList.map(jsObject => (jsObject \ "_id").get.as[JsString].value).asRight[FormError])
      .recover {
        case e => MongoGenericError(e.getMessage).asLeft[List[String]]
      }

  def formsSource(
    projectId: String,
    batchSize: Int,
    afterObjectId: Option[BSONObjectID],
    untilInstant: Instant // inclusive
  ): Source[Form, Future[Unit]] = {

    logger.info(
      s"Fetching forms from ${afterObjectId.map(aoid => Instant.ofEpochMilli(aoid.time))}(exclusive) until $untilInstant(inclusive) for project $projectId"
    )
    val afterObjectSelector: JsObject = afterObjectId
      .map(aoid => obj("$gt" -> ReactiveMongoFormats.objectIdWrite.writes(aoid)))
      .getOrElse(obj())

    val selector = obj(
      "projectId" -> toJson(projectId),
      "_id" -> (obj(
        "$lte" -> ReactiveMongoFormats.objectIdWrite.writes(BSONObjectID.fromTime(untilInstant.toEpochMilli))
      ) ++ afterObjectSelector)
    )

    collection
      .find(selector, Option.empty[JsObject])
      .options(QueryOpts().batchSize(batchSize))
      .cursor[Form]()
      .documentSource()
      .mapMaterializedValue(_ => Future.successful(()))
  }

  def remove(untilInstant: Instant)(implicit ec: ExecutionContext): Future[Unit] = {
    val selectQuery = obj("submissionTimestamp" -> obj("$lt" -> BSONDateTime(untilInstant.toEpochMilli)))
    collection.delete.one(selectQuery, None).map(_ => ())
  }

  object MongoError {
    def unapply(lastError: LastError): Option[(Option[Int], String)] = Some((lastError.code, lastError.message))
  }
}
