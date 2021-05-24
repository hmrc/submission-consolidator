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

package consolidator.repositories

import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.{ JsString, Json }
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{ Index, IndexType }
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ConsolidatorJobDataRepository @Inject()(mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[ConsolidatorJobData, BSONObjectID](
      collectionName = "consolidator_job_datas",
      mongo = mongoComponent.mongoConnector.db,
      domainFormat = ConsolidatorJobData.formats,
      idFormat = ReactiveMongoFormats.objectIdFormats
    ) {
  import ImplicitBSONHandlers._

  override val logger: Logger = LoggerFactory.getLogger(getClass)

  override def indexes: Seq[Index] =
    Seq(
      Index(
        key = Seq("projectId" -> IndexType.Ascending),
        name = Some("jobIdIdx")
      ),
      Index(
        key = Seq("lastObjectId" -> IndexType.Ascending),
        name = Some("lastObjectIdIdx")
      ),
      Index(
        key = Seq("endTimestamp" -> IndexType.Ascending),
        name = Some("endTimestampIdx")
      )
    )

  def add(
    consolidatorJobData: ConsolidatorJobData
  )(implicit ec: ExecutionContext): Future[Either[ConsolidatorJobDataError, Unit]] =
    insert(consolidatorJobData)
      .map(_ => Right(()))
      .recover {
        case e => Left(GenericConsolidatorJobDataError(e.getMessage))
      }

  /**
    *  Gets the most recent ConsolidatorJobData for the given project id, based on endTimestamp.
    *
    * @param projectId The project id to get recent ConsolidatorJobData for
    * @param ec The execution context
    * @return
    */
  def findRecentLastObjectId(
    projectId: String
  )(implicit ec: ExecutionContext): Future[Either[ConsolidatorJobDataError, Option[ConsolidatorJobData]]] = {
    import collection.BatchCommands.AggregationFramework._

    // considers records that have lastObjectId defined i.e successful job execution
    val matchQueryStage = Match(Json.obj("projectId" -> projectId, "lastObjectId" -> Json.obj("$exists" -> true)))

    // get the max endTimestamp
    val groupStage = Group(Json.obj())(
      "maxEndTimestamp" -> Max(JsString("$endTimestamp")),
      "docs"            -> Push(JsString("$$ROOT"))
    )

    // project record with max endTimestamp value
    val projectStage = Project(
      Json.obj(
        "maxDoc" -> Filter(
          JsString("$docs"),
          "doc",
          Json.obj(
            "$eq" -> Json.arr("$$doc.endTimestamp", "$maxEndTimestamp")
          )
        )
      )
    )

    val unwindStage = UnwindField("maxDoc")

    val replaceRootStage = ReplaceRootField("maxDoc")

    collection
      .aggregateWith[ConsolidatorJobData]()(_ =>
        matchQueryStage -> List(groupStage, projectStage, unwindStage, replaceRootStage))
      .headOption
      .map(Right(_))
      .recover {
        case e =>
          logger.error(s"findMostRecentLastObjectId failed [projectId=$projectId]", e)
          Left(GenericConsolidatorJobDataError(e.getMessage))
      }
  }
}
