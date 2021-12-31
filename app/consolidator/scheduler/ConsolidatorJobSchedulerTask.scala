/*
 * Copyright 2021 HM Revenue & Customs
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

package consolidator.scheduler

import akka.actor.{ ActorSystem, Props }
import common.MetricsClient
import consolidator.FormConsolidatorActor
import consolidator.repositories.ConsolidatorJobDataRepository
import consolidator.services.{ ConsolidatorService, DeleteDirService, FormService, SubmissionService }
import javax.inject.Inject
import org.slf4j.{ Logger, LoggerFactory }
import play.api.inject.ApplicationLifecycle
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockMongoRepository

import scala.concurrent.Future

class ConsolidatorJobSchedulerTask @Inject()(
  jobScheduler: ConsolidatorJobScheduler,
  consolidatorService: ConsolidatorService,
  fileUploaderService: SubmissionService,
  deleteDirService: DeleteDirService,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  mongoComponent: ReactiveMongoComponent,
  metricsClient: MetricsClient,
  applicationLifecycle: ApplicationLifecycle,
  formService: FormService
)(implicit system: ActorSystem) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  logger.info("Scheduling consolidator jobs")

  val formConsolidatorActorProps: Props = FormConsolidatorActor
    .props(
      consolidatorService,
      fileUploaderService,
      consolidatorJobDataRepository,
      LockMongoRepository(mongoComponent.mongoConnector.db),
      metricsClient,
      deleteDirService,
      formService
    )
  val scheduler = jobScheduler.scheduleJobs(formConsolidatorActorProps)
  applicationLifecycle.addStopHook { () =>
    Future.successful(scheduler.shutdown(true))
  }
}
