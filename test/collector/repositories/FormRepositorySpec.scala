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

import com.softwaremill.diffx.scalatest.DiffMatcher
import consolidator.repositories.FormsMetadata
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

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }

class FormRepositorySpec
    extends AnyWordSpec with ScalaCheckDrivenPropertyChecks with Matchers with DataGenerators
    with EmbeddedMongoDBSupport with BeforeAndAfterAll with ScalaFutures with BeforeAndAfterEach with DiffMatcher {

  override implicit val patienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

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

  "getForms" should {
    "return forms matching the given formId, templateId, with submissionTimestamp higher then the given value" in {
      //given
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      assert(formRepository.addForm(form).futureValue.isRight)

      //when
      val future = for {
        addFormResult <- formRepository.getForms(form.projectId, 1)()
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult shouldBe Right(List(form))
      }
    }

    "return forms based on batch size" in {
      //given
      val projectId = "some-project-id"
      val forms = (1 to 3)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
        .toList
      forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))

      //when
      val future = for {
        addFormResult <- formRepository.getForms(projectId, 2)()
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult.right.get should matchTo(forms.take(2))
      }
    }

    "fetch batch after the given afterObjectId" in {
      //given
      val projectId = "some-project-id"
      val forms = (1 to 3)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
        .toList
      forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))

      //when
      val afterObjectId = forms(1).id
      val future = for {
        addFormResult <- formRepository.getForms(projectId, 2)(Some(afterObjectId))
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult.right.get should matchTo(forms.filter(_.id.stringify > afterObjectId.stringify))
      }
    }

    "fetch batch after the given afterObjectId and until the given untilObjectId (inclusive)" in {
      val projectId = "some-project-id"
      val forms = (1 to 4)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
        .toList
      forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))

      //when
      val afterObjectId = forms(1).id
      val untilObjectId = forms(2).id
      val future = for {
        addFormResult <- formRepository.getForms(projectId, 2)(Some(afterObjectId), Some(untilObjectId))
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult.right.get should matchTo(
          forms.filter(_.id.stringify > afterObjectId.stringify).filter(_.id.stringify <= untilObjectId.stringify))
      }
    }

    "fetch batch until the given untilObjectId (inclusive)" in {
      val projectId = "some-project-id"
      val forms = (1 to 4)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
        .toList
      forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))

      //when
      val untilObjectId = forms(2).id
      val future = for {
        addFormResult <- formRepository.getForms(projectId, 4)(None, Some(untilObjectId))
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult.right.get should matchTo(forms.filter(_.id.stringify <= untilObjectId.stringify))
      }
    }

    "handle error on failure" in {
      //given
      val form = genForm.pureApply(Gen.Parameters.default, Seed(1))
      assert(formRepository.addForm(form).futureValue.isRight)
      stopMongoD()

      //when
      val future = for {
        addFormResult <- formRepository.getForms(form.projectId, 1)()
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult.isLeft shouldBe true
        addFormResult.left.get shouldBe a[MongoGenericError]
        addFormResult.left.get.getMessage contains "MongoError['No primary node is available!"
        init()
      }
    }
  }

  "getFormsMetadata" should {

    "retun None when there is no form data" in {
      val projectId = "some-project-id"
      assert(formRepository.findAll().futureValue.isEmpty)

      val future = for {
        addFormResult <- formRepository.getFormsMetadata(projectId)
      } yield addFormResult
      whenReady(future) { addFormResult =>
        addFormResult shouldBe Right(None)
      }
    }

    "return the count and max object id, in the group for the given project id" in {
      //given
      implicit val bsonIdOrdering: Ordering[BSONObjectID] =
        (left: BSONObjectID, right: BSONObjectID) => left.stringify.compareTo(right.stringify)
      val projectId = "some-project-id"
      val forms = (1 to 3)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
        .toList
      forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))

      //when
      val future = for {
        addFormResult <- formRepository.getFormsMetadata(projectId)
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult shouldBe Right(Some(FormsMetadata(forms.size, forms.map(_.id).max)))
      }
    }

    "return the count and max object id, in the group for the given project id, after the given object id" in {
      //given
      implicit val bsonIdOrdering: Ordering[BSONObjectID] =
        (left: BSONObjectID, right: BSONObjectID) => left.stringify.compareTo(right.stringify)
      val projectId = "some-project-id"
      val forms = (1 to 3)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(projectId = projectId))
        .toList
      forms.foreach(form => assert(formRepository.addForm(form).futureValue.isRight))

      //when
      val future = for {
        addFormResult <- formRepository.getFormsMetadata(projectId, Some(forms.head.id))
      } yield addFormResult

      //then
      whenReady(future) { addFormResult =>
        addFormResult shouldBe Right(Some(FormsMetadata(forms.drop(1).size, forms.drop(1).map(_.id).max)))
      }
    }
  }

  "add form test" should {
    "persist all forms in" in {
      import scala.concurrent.duration._
      val genForm = for {
        submissionRef       <- Gen.uuid.map(_.toString)
        projectId           <- Gen.const("test-project")
        templateId          <- Gen.const("test-template")
        customerId          <- Gen.const("test-customer")
        submissionTimestamp <- genInstant
        formData            <- Gen.const(List.empty)
      } yield
        Form(
          submissionRef,
          projectId,
          templateId,
          customerId,
          submissionTimestamp,
          formData
        )
      val forms = (1 to 30)
        .map(seed => genForm.pureApply(Gen.Parameters.default, Seed(seed)).copy(submissionTimestamp = Instant.now()))
        .toList
      val futures: immutable.Seq[Future[Either[FormError, Unit]]] = forms.map(form => formRepository.addForm(form))
      val result = Await.result(Future.sequence(futures), 10.seconds)
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
