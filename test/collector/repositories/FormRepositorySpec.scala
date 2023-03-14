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

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import com.softwaremill.diffx.scalatest.DiffMatcher
import com.typesafe.config.ConfigFactory
import org.bson.types.ObjectId
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
import play.api.Configuration
import uk.gov.hmrc.mongo.MongoComponent

import java.time.Instant
import java.util.Date
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
    formRepository.collection.deleteMany(Document()).toFuture()

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
    forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))
  }

  "addForm" should {
    "add the form data to the forms collection" in {
      forAll(genForm) { form =>
        val future: Future[Option[Form]] = for {
          _      <- formRepository.addForm(form)
          dbForm <- formRepository.collection.find(Filters.equal("_id", form._id)).headOption()
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

      val future = formRepository.addForm(form.copy(_id = form._id, submissionRef = "test"))
      whenReady(future) { addFormResult =>
        addFormResult.left
          .getOrElse("E11000 duplicate key error collection")
          .toString contains "E11000 duplicate key error collection"
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
        addFormResult.left.getOrElse(a[MongoUnavailable]) shouldBe a[MongoUnavailable]
        addFormResult.left.getOrElse("MongoUnavailable").toString contains "MongoUnavailable"

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
        result.toOption.get shouldBe forms.flatMap(_.formData).map(_.id).distinct.sorted
      }
    }
  }

  private def buildFormRepository(mongoHost: String, mongoPort: Int) = {
    val uri = s"mongodb://$mongoHost:$mongoPort/submission-consolidator"
    val mongo = MongoComponent(uri)

    val config =
      Configuration(ConfigFactory.parseString("""
                                                | mongodb {
                                                |     timeToLiveInSeconds = 100
                                                |     }
                                                |""".stripMargin))
    new FormRepository(mongo, config)
  }
}
