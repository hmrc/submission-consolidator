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

import org.mongodb.scala.bson.{ BsonArray, BsonDocument, Document }
import org.mongodb.scala.model.Aggregates.{ replaceRoot, unwind }
import org.mongodb.scala.model.Filters.{ and, equal, exists }
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections.computed
import org.mongodb.scala.model._
import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ConsolidatorJobDataRepository @Inject() (mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[ConsolidatorJobData](
      mongoComponent = mongo,
      collectionName = "consolidator_job_datas",
      domainFormat = ConsolidatorJobData.format,
      indexes = Seq(
        IndexModel(ascending("projectId"), IndexOptions().name("jobIdIdx")),
        IndexModel(ascending("lastObjectId"), IndexOptions().name("lastObjectIdIdx")),
        IndexModel(ascending("endTimestamp"), IndexOptions().name("endTimestampIdx")),
        IndexModel(ascending("envelopeId"), IndexOptions().name("envelopeId"))
      ),
      replaceIndexes = true
    ) {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  def add(
    consolidatorJobData: ConsolidatorJobData
  )(implicit ec: ExecutionContext): Future[Either[ConsolidatorJobDataError, Unit]] =
    collection
      .insertOne(consolidatorJobData)
      .toFuture()
      .map(_ => Right(()))
      .recover { case e =>
        Left(GenericConsolidatorJobDataError(e.getMessage))
      }

  /**  Gets the most recent ConsolidatorJobData for the given project id, based on endTimestamp.
    *
    * @param projectId The project id to get recent ConsolidatorJobData for
    * @param ec The execution context
    * @return
    */
  def findRecentLastObjectId(
    projectId: String
  )(implicit ec: ExecutionContext): Future[Either[ConsolidatorJobDataError, Option[ConsolidatorJobData]]] = {

    // considers records that have lastObjectId defined i.e successful job execution
    val filter = Aggregates.filter(and(equal("projectId", projectId), exists("lastObjectId")))
    // get the max endTimestamp
    val group = Aggregates.group(
      "",
      Accumulators.max("maxEndTimestamp", "$endTimestamp"),
      Accumulators.push("docs", "$$ROOT")
    )
    // project record with max endTimestamp value
    val project = Aggregates.project(
      computed(
        "maxDoc",
        BsonDocument(
          "$filter" -> Document(
            "input" -> "$docs",
            "as"    -> "doc",
            "cond"  -> Document("$eq" -> BsonArray("$$doc.endTimestamp", "$maxEndTimestamp"))
          )
        )
      )
    )

    val unwindStage = unwind("$maxDoc")
    val replaceRootStage = replaceRoot("$maxDoc")

    val pipeline = List(filter, group, project, unwindStage, replaceRootStage)

    collection
      .aggregate[ConsolidatorJobData](pipeline)
      .headOption()
      .map(Right(_))
      .recover { case e =>
        logger.error(s"findMostRecentLastObjectId failed [projectId=$projectId]", e)
        Left(GenericConsolidatorJobDataError(e.getMessage))
      }
  }

  def findByEnvelopeId(envelopeId: String) =
    collection.find(equal("envelopeId", envelopeId)).first().toFuture()
}
