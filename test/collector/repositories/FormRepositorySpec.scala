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

import com.softwaremill.diffx.scalatest.DiffMatcher
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.bson.types.ObjectId
import org.mockito.MockitoSugar.mock
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.model.Filters
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.Configuration
import uk.gov.hmrc.mongo.MongoUtils
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FormRepositorySpec
    extends AnyWordSpec with ScalaCheckDrivenPropertyChecks with Matchers with DataGenerators
    with DefaultPlayMongoRepositorySupport[Form] with BeforeAndAfterAll with ScalaFutures with BeforeAndAfterEach
    with DiffMatcher {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))
  implicit val system: ActorSystem = ActorSystem("FormRepositorySpec")
  private lazy val config: Configuration = mock[Configuration]

  val collection: MongoCollection[Form] = mongoComponent.database.getCollection(collectionName = "forms")
  MongoUtils.ensureIndexes(collection, List(), replaceIndexes = true).futureValue

  override protected val repository: FormRepository = new FormRepository(mongoComponent, config)

  override def beforeEach(): Unit =
    prepareDatabase()

  trait FormsTestFixture {
    val projectId = "some-project-id"
    val currentTimeInMillis = System.currentTimeMillis()
    val untilTime = Instant.ofEpochMilli(currentTimeInMillis + 1000) // 1 second after current time
    val afterObjectId = ObjectId.getSmallestWithDate(
      Date.from(Instant.ofEpochMilli(currentTimeInMillis - 1000))
    ) // 1 second before current time
    lazy val forms = (1 to 3)
      .map(seed =>
        genForm
          .pureApply(Gen.Parameters.default, Seed(seed.toLong))
          .copy(projectId = projectId)
      )
      .toList
    forms.foreach(form => assert(repository.addForm(form).futureValue.isRight))
  }

  "addForm" should {
    "add the form data to the forms collection" in {
      forAll(genForm) { form =>
        val future: Future[Option[Form]] = for {
          _      <- repository.addForm(form)
          dbForm <- repository.collection.find(Filters.equal("_id", form._id)).headOption()
        } yield dbForm
        whenReady(future) { dbForm =>
          dbForm.map(truncatedTimeToDay) shouldBe Some(form).map(truncatedTimeToDay)
        }
      }
    }

    "return an DuplicateSubmissionRef error if submissionRef already exists in the forms collection" in {
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      assert(repository.addForm(form).futureValue.isRight)

      val duplicateForm = genForm.pureApply(Gen.Parameters.default, Seed(2)).copy(submissionRef = form.submissionRef)
      val future = repository.addForm(duplicateForm)

      whenReady(future) { addFormResult =>
        addFormResult shouldBe Left(DuplicateSubmissionRef(form.submissionRef, "submissionRef must be unique"))
      }
    }

    "return a GenericError for all other errors" in {
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      assert(repository.addForm(form).futureValue.isRight)

      val future = repository.addForm(form.copy(_id = form._id, submissionRef = "test"))
      whenReady(future) { addFormResult =>
        addFormResult.left
          .getOrElse("E11000 duplicate key error collection")
          .toString contains "E11000 duplicate key error collection"
      }
    }
  }

  "formsSource" should {

    "return forms before the given creation time" in new FormsTestFixture {

      val source = repository.formsSource(projectId, forms.size, None, untilTime)

      val future = source.runWith(Sink.seq[Form])

      whenReady(future) { result =>
        result.map(truncatedTimeToDay) shouldEqual forms.map(truncatedTimeToDay)
      }
    }

    "return forms after given object id, before the given creation time" in new FormsTestFixture {

      val source = repository.formsSource(projectId, forms.size, Some(afterObjectId), untilTime)

      val future = source.runWith(Sink.seq[Form])

      whenReady(future) { result =>
        result.map(truncatedTimeToDay) shouldEqual forms.map(truncatedTimeToDay)
      }
    }
  }

  "distinctFormDataIds" should {

    "return distinct formData ids" in new FormsTestFixture {

      val future: Future[Either[FormError, List[String]]] =
        repository.distinctFormDataIds(projectId, Some(afterObjectId))

      whenReady(future) { result =>
        result.isRight shouldBe true
        result.toOption.get shouldBe forms.flatMap(_.formData).map(_.id).distinct.sorted
      }
    }
  }

  private def truncatedTimeToDay(f: Form) = f.copy(
    submissionTimestamp = f.submissionTimestamp.truncatedTo(ChronoUnit.DAYS)
  )
}
