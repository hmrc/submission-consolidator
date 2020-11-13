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

package consolidator.controllers

import java.awt.GraphicsEnvironment
import java.time.{ LocalDate, ZoneId }
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import collector.controllers.ErrorCode.{ CONSOLIDATOR_JOB_ALREADY_IN_PROGRESS, INVALID_CONSOLIDATOR_JOB_ID, MANUAL_CONSOLIDATION_FAILED }
import collector.controllers.{ ErrorHandler, ManualConsolidationError }
import com.typesafe.akka.`extension`.quartz.MessageWithFireTime
import com.typesafe.config.ConfigRenderOptions
import consolidator.FormConsolidatorActor
import consolidator.scheduler.ConsolidatorJobConfig
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ManualConsolidationController @Inject()(controllerComponents: ControllerComponents, config: Configuration)(
  implicit
  system: ActorSystem)
    extends BackendController(controllerComponents) with ErrorHandler {

  private val logger: Logger = LoggerFactory.getLogger(classOf[ManualConsolidationController])

  implicit val timeout: Timeout = Timeout(5, TimeUnit.MINUTES)

  implicit val ec: ExecutionContext = system.dispatcher

  val consolidatorJobConfigs: Seq[ConsolidatorJobConfig] = config.underlying
    .getConfigList("consolidator-jobs")
    .asScala
    .toList
    .map(c => Json.parse(c.root().render(ConfigRenderOptions.concise())).as[ConsolidatorJobConfig])

  def consolidateAndSubmit(consolidatorJobId: String, startDate: String, endDate: String) =
    Action.async { _ =>
      val time = System.currentTimeMillis()
      logger.info("Headless " + GraphicsEnvironment.isHeadless)
      logger.info(
        s"Manual consolidation triggered [consolidatorJobId=$consolidatorJobId, startDate=$startDate, endDate=$endDate]"
      )
      (for {
        consolidatorActor <- system.actorSelection(s"/user/$consolidatorJobId/").resolveOne()
        result <- consolidatorJobConfigs
                   .find(_.id == consolidatorJobId)
                   .map { consolidatorJobConfig =>
                     val params = consolidatorJobConfig.params.toManualFormConsolidatorParams(
                       startInstant(startDate),
                       endInstant(endDate)
                     )
                     logger.info(s"Sending message with job params $params [consolidatorJobId=$consolidatorJobId]")
                     (consolidatorActor ? MessageWithFireTime(params, new Date())).map { consolidatorResponse =>
                       logger.info(s"Consolidator job completed [consolidatorJobId=$consolidatorJobId] in ${(System
                         .currentTimeMillis() - time) / 1000} seconds")
                       consolidatorResponse match {
                         case FormConsolidatorActor.OK => NoContent
                         case FormConsolidatorActor.LockUnavailable =>
                           handleError(
                             ManualConsolidationError(
                               CONSOLIDATOR_JOB_ALREADY_IN_PROGRESS,
                               s"Consolidator job already in progress [consolidatorJobId=$consolidatorJobId]"))
                         case other =>
                           val message =
                             s"Failed to consolidate forms [consolidatorJobId=$consolidatorJobId, error=$other]"
                           logger.error(message)
                           handleError(ManualConsolidationError(MANUAL_CONSOLIDATION_FAILED, message))
                       }
                     }
                   }
                   .getOrElse {
                     val message = s"Invalid consolidator job, no actor found [consolidatorJobId=$consolidatorJobId]"
                     logger.error(message)
                     Future.successful(
                       handleError(ManualConsolidationError(INVALID_CONSOLIDATOR_JOB_ID, message))
                     )
                   }
      } yield result).recover {
        case exception =>
          val message = s"Failed to consolidate forms [consolidatorJobId=$consolidatorJobId, error=$exception]"
          logger.error(message, exception)
          handleError(
            ManualConsolidationError(MANUAL_CONSOLIDATION_FAILED, message)
          )
      }
    }

  private def endInstant(endDate: String) =
    LocalDate.parse(endDate).atTime(23, 59, 59, 0).atZone(ZoneId.systemDefault()).toInstant

  private def startInstant(startDate: String) =
    LocalDate.parse(startDate).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant
}
