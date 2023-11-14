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

package consolidator.controllers

import collector.controllers.ErrorHandler
import consolidator.repositories.{ CorrelationId, NotificationStatus, SdesReportData }
import consolidator.services.{ SdesService, SubmissionService }
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class SdesController @Inject() (
  controllerComponents: ControllerComponents,
  sdesService: SdesService,
  submissionService: SubmissionService
)(implicit ec: ExecutionContext)
    extends BackendController(controllerComponents) with ErrorHandler {
  def search(
    page: Int,
    pageSize: Int,
    processed: Option[Boolean],
    status: Option[NotificationStatus],
    showBeforeAt: Option[Boolean]
  ) = Action.async { _ =>
    sdesService
      .search(page, pageSize, processed, status, showBeforeAt)
      .map(pageData => Ok(Json.toJson(pageData)))
  }

  def notifySDES(correlationId: CorrelationId) = Action.async { _ =>
    sdesService.notifySDESById(correlationId).map { _ =>
      NoContent
    }
  }

  def find(correlationId: CorrelationId) = Action.async { _ =>
    sdesService.find(correlationId).flatMap {
      case Some(s) => Future.successful(Ok(Json.toJson(SdesReportData.fromSdesSubmission(s))))
      case None    => Future.failed(new RuntimeException(s"Correlation id [$correlationId] not found in mongo collection"))
    }
  }

  def updateAsManualConfirmed(correlationId: CorrelationId) = Action.async { _ =>
    sdesService.updateAsManualConfirmed(correlationId).map { _ =>
      NoContent
    }
  }

}
