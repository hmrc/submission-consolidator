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

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import com.softwaremill.diffx.scalatest.DiffMatcher
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.MongoConnector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FormRepositorySpec
    extends AnyWordSpec with ScalaCheckDrivenPropertyChecks with Matchers with DataGenerators
    with EmbeddedMongoDBSupport with BeforeAndAfterAll with ScalaFutures with BeforeAndAfterEach with DiffMatcher {

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))
  implicit val system = ActorSystem("FormRepositorySpec")

  var formRepository: FormRepository = _

  override def beforeAll(): Unit =
    init()

  override def beforeEach(): Unit =
    formRepository.removeAll().futureValue

  private def init() = {
    initMongoDExecutable()
    startMongoD()
    formRepository = buildFormRepository(mongoHost, mongoPort)
  }

  override def afterAll(): Unit =
    stopMongoD()

  trait FormsTestFixture {
    val projectId = "some-project-id"
    val currentTimeInMillis = System.currentTimeMillis()
    val untilTime = Instant.ofEpochMilli(currentTimeInMillis + 1000) // 1 second after current time
    val afterObjectId = BSONObjectID.fromTime(currentTimeInMillis - 1000, false) // 1 second before current time
    lazy val forms = (1 to 3)
      .map(
        seed =>
          genForm
            .pureApply(Gen.Parameters.default, Seed(seed.toLong))
            .copy(projectId = projectId, id = BSONObjectID.fromTime(currentTimeInMillis, false)))
      .toList
    forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))
  }

  "addForm" should {
    "add the form data to the forms collection" in {
      forAll(genForm) { form =>
        val future: Future[Option[Form]] = for {
          _      <- formRepository.addForm(form)
          dbForm <- formRepository.findById(form.id)
        } yield dbForm
        whenReady(future) { dbForm =>
          dbForm shouldBe Some(form)
        }
      }
    }

    "return an DuplicateSubmissionRef error if submissionRef already exists in the forms collection" in {
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      assert(formRepository.addForm(form).futureValue.isRight)

      val duplicateForm = genForm.pureApply(Gen.Parameters.default, Seed(2)).copy(submissionRef = form.submissionRef)
      val future = formRepository.addForm(duplicateForm)

      whenReady(future) { addFormResult =>
        addFormResult shouldBe Left(DuplicateSubmissionRef(form.submissionRef, "submissionRef must be unique"))
      }
    }

    "return a GenericError for all other errors" in {
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      assert(formRepository.addForm(form).futureValue.isRight)

      val future = formRepository.addForm(form.copy(id = form.id))
      whenReady(future) { addFormResult =>
        addFormResult shouldBe Left(
          MongoGenericError(
            s"DatabaseException['E11000 duplicate key error collection: submission-consolidator.forms index: _id_ dup key: { : ObjectId('${form.id.stringify}') }' (code = 11000)]"
          )
        )
      }
    }

    "return a unavailable error when mongodb is unavailable" in {
      stopMongoD()
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      val future = for {
        addFormResult <- formRepository.addForm(form)
      } yield addFormResult
      whenReady(future) { addFormResult =>
        addFormResult.isLeft shouldBe true
        addFormResult.left.get shouldBe a[MongoUnavailable]
        addFormResult.left.get.getMessage contains "MongoError['No primary node is available!"
        init()
      }
    }
  }

  "formsSource" should {

    "return forms before the given creation time" in new FormsTestFixture {

      val source = formRepository.formsSource(projectId, forms.size, None, untilTime)

      val future = source.runWith(Sink.seq[Form])

      whenReady(future) { result =>
        result shouldEqual forms
      }
    }

    "return forms after given object id, before the given creation time" in new FormsTestFixture {

      val source = formRepository.formsSource(projectId, forms.size, Some(afterObjectId), untilTime)

      val future = source.runWith(Sink.seq[Form])

      whenReady(future) { result =>
        result shouldEqual forms
      }
    }
  }

  "distinctFormDataIds" should {

    "return distinct formData ids" in new FormsTestFixture {

      val future: Future[Either[FormError, List[String]]] =
        formRepository.distinctFormDataIds(projectId, Some(afterObjectId))

      whenReady(future) { result =>
        result.isRight shouldBe true
        result.right.get shouldBe forms.flatMap(_.formData).map(_.id).distinct.sorted
      }
    }

    "return error when aggregation fails" in new FormsTestFixture {
      stopMongoD()
      val future: Future[Either[FormError, List[String]]] =
        formRepository.distinctFormDataIds(projectId, Some(afterObjectId))
      whenReady(future) { result =>
        result.isLeft shouldBe true
        result.left.get shouldBe a[MongoGenericError]
        result.left.get.getMessage contains "MongoError['No primary node is available!"
        init()
      }
    }
  }

  private def buildFormRepository(mongoHost: String, mongoPort: Int) = {
    val connector =
      MongoConnector(s"mongodb://$mongoHost:$mongoPort/submission-consolidator")
    val reactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector =
        connector
    }
    new FormRepository(reactiveMongoComponent)
  }
}
