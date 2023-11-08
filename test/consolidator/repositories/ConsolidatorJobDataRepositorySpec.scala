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

import collector.repositories.DataGenerators
import org.mongodb.scala.Document
import org.mongodb.scala.model.Filters
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global

class ConsolidatorJobDataRepositorySpec
    extends AnyWordSpec with Matchers with DefaultPlayMongoRepositorySupport[ConsolidatorJobData] with BeforeAndAfterAll
    with BeforeAndAfterEach with ScalaFutures with DataGenerators with ScalaCheckDrivenPropertyChecks {

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

  var repository: ConsolidatorJobDataRepository = new ConsolidatorJobDataRepository(mongoComponent)

  override def beforeEach(): Unit =
    repository.collection.deleteMany(Document()).toFuture().futureValue

  "add" should {
    "persist consolidator job data with last object id" in {
      forAll(genConsolidatorJobData) { consolidatorJobData =>
        val future = repository.add(consolidatorJobData)

        whenReady(future) { result =>
          result shouldBe Right(())
          repository.collection
            .find(Filters.equal("_id", consolidatorJobData._id))
            .headOption()
            .futureValue
            .map(truncatedTimeToDay) shouldBe Some(
            consolidatorJobData
          ).map(truncatedTimeToDay)
        }
      }
    }

    "persist consolidator job data with error" in {
      forAll(genConsolidatorJobDataWithError) { consolidatorJobData =>
        val future = repository.add(consolidatorJobData)

        whenReady(future) { result =>
          result shouldBe Right(())
          repository.collection
            .find(Filters.equal("_id", consolidatorJobData._id))
            .headOption()
            .futureValue
            .map(truncatedTimeToDay) shouldBe Some(
            consolidatorJobData
          ).map(truncatedTimeToDay)
        }
      }
    }

    "findRecentLastObjectId" should {

      "return None, when no records exist" in {

        whenReady(repository.collection.find().headOption()) { result =>
          assert(result.isEmpty)
        }

        val future = repository.findRecentLastObjectId("some-project-id")

        whenReady(future) { result =>
          result shouldBe Right(None)
        }
      }

      "return None, when all of records have errors" in {
        val projectId = "some-project-id"
        val consolidatorJobDatasWithErrors = (1 to 10).map(seed =>
          genConsolidatorJobDataWithError
            .pureApply(Gen.Parameters.default, Seed(seed.toLong))
            .copy(projectId = projectId)
        )
        assert(consolidatorJobDatasWithErrors.forall(_.error.isDefined))
        consolidatorJobDatasWithErrors.foreach(repository.add(_).futureValue)

        val future = repository.findRecentLastObjectId(projectId)

        whenReady(future) { result =>
          result shouldBe Right(None)
        }
      }

      "return most recent job data having lastObjectId, for the given projectId" in {
        val projectId = "some-project-id"
        val consolidatorJobDatas = (1 to 10).map(seed =>
          genConsolidatorJobData.pureApply(Gen.Parameters.default, Seed(seed.toLong)).copy(projectId = projectId)
        )
        assert(consolidatorJobDatas.forall(_.lastObjectId.isDefined))
        consolidatorJobDatas.foreach(repository.add(_).futureValue)

        val future = repository.findRecentLastObjectId(projectId)

        whenReady(future) { result =>
          result.map(_.map(truncatedTimeToDay)) shouldBe Right(
            Some(consolidatorJobDatas.maxBy(_.endTimestamp)).map(truncatedTimeToDay)
          )
        }
      }

      "return most recent job data having lastObjectId, ignoring records with errors" in {
        val projectId = "some-project-id"
        val consolidatorJobDatas = (1 to 5).map(seed =>
          genConsolidatorJobData
            .pureApply(Gen.Parameters.default, Seed(seed.toLong))
            .copy(projectId = projectId)
        ) ++ (1 to 5)
          .map(seed =>
            genConsolidatorJobDataWithError
              .pureApply(Gen.Parameters.default, Seed(seed.toLong))
              .copy(projectId = projectId)
          )
        assert(consolidatorJobDatas.exists(_.error.isDefined))
        consolidatorJobDatas.foreach(repository.add(_).futureValue)

        val future = repository.findRecentLastObjectId(projectId)

        whenReady(future) { result =>
          result.map(_.map(truncatedTimeToDay)) shouldBe Right(
            Some(consolidatorJobDatas.filter(_.lastObjectId.isDefined).maxBy(_.endTimestamp))
              .map(truncatedTimeToDay)
          )
        }
      }
    }
  }

  private def truncatedTimeToDay(c: ConsolidatorJobData) = c.copy(
    startTimestamp = c.startTimestamp.truncatedTo(ChronoUnit.SECONDS),
    endTimestamp = c.endTimestamp.truncatedTo(ChronoUnit.SECONDS)
  )
}
