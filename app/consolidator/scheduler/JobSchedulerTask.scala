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

package consolidator.scheduler

import akka.actor.ActorSystem
import consolidator.FormConsolidatorActor
import javax.inject.Inject
import org.slf4j.{ Logger, LoggerFactory }
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

class JobSchedulerTask @Inject()(jobScheduler: JobScheduler, applicationLifecycle: ApplicationLifecycle) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  logger.info("Scheduling consolidator jobs")

  implicit val system = ActorSystem("JobSchedulerSystem")
  val scheduler = jobScheduler.scheduleJobs(FormConsolidatorActor.props())
  applicationLifecycle.addStopHook { () =>
    Future.successful(scheduler.shutdown(true))
  }
}
