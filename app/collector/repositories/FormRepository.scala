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

import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.Json.{ obj, toJson }
import play.api.libs.json.JsObject
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
          "templateId" -> IndexType.Ascending,
          "formId"     -> IndexType.Ascending
        ),
        name = Some("templateIdFormIdIdx")
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

  def getForms(
    formId: String,
    templateId: String,
    batchSize: Int,
    lastObjectId: Option[BSONObjectID] = None
  )(
    implicit
    ec: ExecutionContext): Future[Either[FormError, List[Form]]] = {

    val selector = JsObject(
      Seq(
        "formId"     -> toJson(formId),
        "templateId" -> toJson(templateId)
      ) ++ lastObjectId.map(oid => "_id" -> obj("$gt" -> ReactiveMongoFormats.objectIdWrite.writes(oid)))
    )

    collection
      .find(selector, Option.empty[JsObject])
      .options(QueryOpts().batchSize(batchSize))
      .cursor[Form]()
      .collect[List](batchSize, FailOnError[List[Form]]())
      .map(Right(_))
      .recover {
        case e => Left(MongoGenericError(e.getMessage))
      }
  }

  object MongoError {
    def unapply(lastError: LastError): Option[(Option[Int], String)] = Some((lastError.code, lastError.message))
  }
}
