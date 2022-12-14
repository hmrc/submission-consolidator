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

package consolidator.repositories

import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{ Index, IndexType }
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class SdesSubmissionRepository @Inject() (mongoComponent: ReactiveMongoComponent)
    extends ReactiveRepository[SdesSubmission, BSONObjectID](
      collectionName = "sdes_submission",
      mongo = mongoComponent.mongoConnector.db,
      domainFormat = SdesSubmission.formats,
      idFormat = ReactiveMongoFormats.objectIdFormats
    ) {
  override def indexes: Seq[Index] =
    Seq(
      Index(
        key = Seq("confirmedAt" -> IndexType.Ascending),
        name = Some("confirmedAtIdx")
      )
    )

  def upsert(sdesSubmission: SdesSubmission)(implicit ec: ExecutionContext): Future[Either[SdesSubmissionError, Unit]] =
    insert(sdesSubmission)
      .map(_ => Right(()))
      .recover { case e =>
        Left(GenericSdesSubmissionError(e.getMessage))
      }

  def findByCorrelationId(correlationId: String)(implicit ec: ExecutionContext): Future[Option[SdesSubmission]] =
    find("correlationId" -> correlationId).map(res => res.headOption)
}
