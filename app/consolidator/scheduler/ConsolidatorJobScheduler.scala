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

import akka.actor.{ ActorSystem, Props }
import com.typesafe.akka.extension.quartz.{ MessageRequireFireTime, QuartzSchedulerExtension }
import com.typesafe.config.ConfigRenderOptions
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import play.api.libs.json.Json.parse

import scala.collection.JavaConverters._

@Singleton
class ConsolidatorJobScheduler @Inject()(config: Configuration) {

  def scheduleJobs(receivingActorProps: Props)(implicit system: ActorSystem): QuartzSchedulerExtension = {
    val consolidatorJobConfigs = config.underlying
      .getConfigList("consolidator-jobs")
      .asScala
      .map(c => parse(c.root().render(ConfigRenderOptions.concise())).as[ConsolidatorJobConfig])

    val scheduler = QuartzSchedulerExtension(system)

    consolidatorJobConfigs.foreach { jobConfig =>
      val receivingActorRef = system.actorOf(receivingActorProps, jobConfig.id)
      scheduler.createJobSchedule(
        jobConfig.id,
        receivingActorRef,
        MessageRequireFireTime(jobConfig.params.toScheduledFormConsolidatorParams),
        None,
        jobConfig.cron)
    }

    scheduler
  }
}
