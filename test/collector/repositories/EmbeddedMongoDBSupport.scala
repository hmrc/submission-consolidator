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

import de.flapdoodle.embed.mongo.{ MongodExecutable, MongodStarter }
import de.flapdoodle.embed.mongo.config.{ MongodConfigBuilder, Net }
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.runtime.Network

trait EmbeddedMongoDBSupport {
  // embedded mondodb instance
  var mongodExecutable: MongodExecutable = _

  def startMongoD() =
    mongodExecutable.start()

  def stopMongoD() =
    mongodExecutable.stop()

  def initMongoDExecutable(host: String, port: Int) =
    mongodExecutable = MongodStarter.getDefaultInstance
      .prepare(
        new MongodConfigBuilder()
          .version(Version.Main.V3_6)
          .net(new Net(host, port, Network.localhostIsIPv6()))
          .build()
      )
}
