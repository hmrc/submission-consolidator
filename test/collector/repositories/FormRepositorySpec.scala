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

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.MongoConnector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FormRepositorySpec
    extends AnyWordSpec with ScalaCheckDrivenPropertyChecks with Matchers with DataGenerators
    with EmbeddedMongoDBSupport with BeforeAndAfterAll with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(Span(10, Seconds), Span(1, Millis))

  val mongoHost = "localhost"
  val mongoPort = 12345
  var formRepository: FormRepository = _

  override def beforeAll(): Unit =
    init()

  private def init() = {
    initMongoDExecutable(mongoHost, mongoPort)
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
      forAll(genForm, genForm) { (form1, form2) =>
        val future = for {
          _             <- formRepository.addForm(form1)
          addFormResult <- formRepository.addForm(form2.copy(submissionRef = form1.submissionRef))
        } yield addFormResult
        whenReady(future) { addFormResult =>
          addFormResult shouldBe Left(DuplicateSubmissionRef(form1.submissionRef, "submissionRef must be unique"))
        }
      }
    }

    "return a GenericError for all other errors" in {
      forAll(genForm, genForm) { (form1, form2) =>
        val future = for {
          _             <- formRepository.addForm(form1)
          addFormResult <- formRepository.addForm(form2.copy(id = form1.id))
        } yield addFormResult
        whenReady(future) { addFormResult =>
          addFormResult shouldBe Left(
            MongoGenericError(
              s"DatabaseException['E11000 duplicate key error collection: submission-consolidator.forms index: _id_ dup key: { : ObjectId('${form1.id.stringify}') }' (code = 11000)]"
            )
          )
        }
      }
    }

    "return a unavailable error when mongodb is unavailable" in {
      stopMongoD()
      val form = genForm.sample.get
      val future = for {
        addFormResult <- formRepository.addForm(form)
      } yield addFormResult
      whenReady(future) { addFormResult =>
        addFormResult shouldBe Left(
          MongoUnavailable("MongoError['No primary node is available! (Supervisor-1/Connection-1)']")
        )
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
