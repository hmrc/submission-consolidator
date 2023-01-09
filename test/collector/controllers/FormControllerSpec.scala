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

package collector.controllers

import akka.actor.{ ActorSystem, ClassicActorSystemProvider }
import akka.stream.{ Materializer, SystemMaterializer }
import org.mockito.captor.ArgCaptor
import org.mockito.{ ArgumentMatchersSugar, IdiomaticMockito }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test.{ FakeRequest, Helpers }
import collector.controllers.APIFormStubs._
import collector.repositories._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class FormControllerSpec
    extends AnyWordSpec with IdiomaticMockito with ArgumentMatchersSugar with ScalaFutures with Matchers
    with TableDrivenPropertyChecks {

  implicit val sys = ActorSystem("FormControllerSpec")
  implicit def matFromSystem(implicit provider: ClassicActorSystemProvider): Materializer =
    SystemMaterializer(provider.classicSystem).materializer

  trait TestFixture {
    val mockFormRepository = mock[FormRepository]
    val formController = new FormController(Helpers.stubControllerComponents(), mockFormRepository, global)
  }

  "addForm" when {
    "form data is valid" should {
      "persist the data via the form repository" in new TestFixture {
        mockFormRepository.addForm(*)(*) shouldReturn Future.successful(Right(()))
        val request = FakeRequest("POST", "/").withBody(validForm)

        val addFormResult = formController.addForm().apply(request)
        whenReady(addFormResult) { result =>
          val expectedForm = APIFormStubs.validForm.validate[APIForm].get.toForm

          val formCaptor = ArgCaptor[Form]
          mockFormRepository.addForm(formCaptor)(*) wasCalled once
          formCaptor.value.submissionRef shouldBe expectedForm.submissionRef
          formCaptor.value.projectId shouldBe expectedForm.projectId
          formCaptor.value.templateId shouldBe expectedForm.templateId
          formCaptor.value.customerId shouldBe expectedForm.customerId
          formCaptor.value.formData shouldBe expectedForm.formData

          result.header.status shouldBe OK
          result.body.isKnownEmpty shouldBe true
        }
      }

      "persist the data via the form repository - form fields are missing" in new TestFixture {
        mockFormRepository.addForm(*)(*) shouldReturn Future.successful(Right(()))
        val request = FakeRequest("POST", "/").withBody(validFormMissingFormData)

        val addFormResult = formController.addForm().apply(request)
        whenReady(addFormResult) { result =>
          result.header.status shouldBe OK
        }
      }

      "persist the data via the form repository - form fields are empty" in new TestFixture {
        mockFormRepository.addForm(*)(*) shouldReturn Future.successful(Right(()))
        val request = FakeRequest("POST", "/").withBody(validFormEmptyFormData)

        val addFormResult = formController.addForm().apply(request)
        whenReady(addFormResult) { result =>
          result.header.status shouldBe OK
        }
      }
    }

    "form has missing or invalid fields" should {

      "return 400 BadRequest" in new TestFixture {

        val table = Table(
          ("request", "path", "message"),
          (formMissingSubmissionRef, "/submissionRef", "Is required"),
          (
            formEmptySubmissionRef,
            "/submissionRef",
            "Must confirm to the format XXXX-XXXX-XXXX, where X is a upper-case alphabet or a number"
          ),
          (formMissingProjectId, "/projectId", "Is required"),
          (formEmptyProjectId, "/projectId", "Minimum length should be 1"),
          (formMissingTemplateId, "/templateId", "Is required"),
          (formEmptyTemplateId, "/templateId", "Minimum length should be 1"),
          (formMissingCustomerId, "/customerId", "Is required"),
          (formEmptyCustomerId, "/customerId", "Minimum length should be 1"),
          (formMissingSubmissionTimestamp, "/submissionTimestamp", "Is required"),
          (
            formInvalidSubmissionTimestamp,
            "/submissionTimestamp",
            "Must confirm to ISO-8601 date-time format YYYY-MM-DD'T'HH:mm:ssZ"
          ),
          (formMissingFormDataId, "/formData(0)/id", "Is required"),
          (formEmptyFormDataId, "/formData(0)/id", "Minimum length should be 1"),
          (formMissingFormDataValue, "/formData(0)/value", "Is required"),
          (formEmptyFormDataValue, "/formData(0)/value", "Minimum length should be 1")
        )

        forAll(table) { (request, path, message) =>
          val addFormResult = formController
            .addForm()
            .apply(FakeRequest("POST", "/").withBody(request))

          whenReady(addFormResult) { result =>
            val error = responseAsAPIError(result)
            error.code shouldBe ErrorCode.REQUEST_VALIDATION_FAILED
            error.message shouldBe "Request body failed validation"
            error.fieldErrors shouldBe List(APIFieldError(path, message))
            result.header.status shouldBe BAD_REQUEST
          }
        }
      }
    }

    "form repo fails with errors" should {
      "return duplicate submission ref error" in new TestFixture {
        mockFormRepository.addForm(*)(*) shouldReturn Future.successful(
          Left(DuplicateSubmissionRef("some-submission-ref", "submissionRef must be unique"))
        )
        val request = FakeRequest("POST", "/").withBody(validForm)

        val addFormResult = formController.addForm().apply(request)
        whenReady(addFormResult) { result =>
          val error = responseAsAPIError(result)
          error.code shouldBe ErrorCode.DUPLICATE_SUBMISSION_REFERENCE
          error.message shouldBe "submissionRef must be unique [some-submission-ref]"
          result.header.status shouldBe CONFLICT
        }
      }
    }

    "form repo fails due to mongodb error" should {
      "return service unavailable error when mongodb cannot be reached" in new TestFixture {
        mockFormRepository.addForm(*)(*) shouldReturn Future.successful(
          Left(MongoUnavailable("Mongo db unavailable"))
        )
        val request = FakeRequest("POST", "/").withBody(validForm)

        val addFormResult = formController.addForm().apply(request)
        whenReady(addFormResult) { result =>
          val error = responseAsAPIError(result)
          error.code shouldBe ErrorCode.SERVICE_UNAVAILABLE
          error.message shouldBe "Mongo db unavailable"
          result.header.status shouldBe SERVICE_UNAVAILABLE
        }
      }

      "return internal server error when mongodb throws a generic error" in new TestFixture {
        mockFormRepository.addForm(*)(*) shouldReturn Future.successful(
          Left(MongoGenericError("Mongo error"))
        )
        val request = FakeRequest("POST", "/").withBody(validForm)

        val addFormResult = formController.addForm().apply(request)
        whenReady(addFormResult) { result =>
          val error = responseAsAPIError(result)
          error.code shouldBe ErrorCode.INTERNAL_ERROR
          error.message shouldBe "Mongo error"
          result.header.status shouldBe INTERNAL_SERVER_ERROR
        }
      }
    }
  }

  private def responseAsAPIError(result: Result): APIError =
    Json.parse(Await.result(result.body.consumeData, 5.seconds).decodeString("utf-8")).as[APIError]
}
