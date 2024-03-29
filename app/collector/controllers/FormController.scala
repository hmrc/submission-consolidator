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

import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.{ JsError, JsResult, JsValue }
import play.api.mvc.{ ControllerComponents, Request }
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import collector.repositories.FormRepository

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class FormController @Inject() (
  controllerComponents: ControllerComponents,
  formRepository: FormRepository,
  ec: ExecutionContext
) extends BackendController(controllerComponents) with ErrorHandler {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  def addForm() =
    Action.async(parse.json) { request: Request[JsValue] =>
      val apiFormResult: JsResult[APIForm] = request.body.validate[APIForm]
      logger.info(s"addForm invoked [projectId=${apiFormResult.map(_.projectId).getOrElse("")}")
      apiFormResult.fold(
        errors => {
          logger.error(s"Request body validation failed [errors=${JsError.toJson(errors)}}")
          Future.successful(handleError(RequestValidationError(errors.map(e => (e._1, e._2.toSeq)).toSeq)))
        },
        valid =>
          formRepository
            .addForm(valid.toForm)(ec)
            .map(
              _.fold(
                handleError,
                _ => Ok
              )
            )(ec)
      )
    }
}
