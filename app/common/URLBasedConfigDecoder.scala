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

package common

import java.net.URLDecoder

import com.typesafe.config.{ ConfigList, ConfigObject, ConfigValue, ConfigValueFactory, ConfigValueType }
import play.api.Configuration

import scala.collection.JavaConverters._
trait URLBasedConfigDecoder {

  private val urlEncPrefix = "urlenc:"

  private def decode(value: String): String = URLDecoder.decode(value, "UTF-8")
  private def decode(value: ConfigValue): ConfigValue =
    value.valueType match {
      case ConfigValueType.STRING if value.unwrapped.asInstanceOf[String].startsWith(urlEncPrefix) =>
        ConfigValueFactory.fromAnyRef(decode(value.unwrapped.asInstanceOf[String].drop(urlEncPrefix.length)))
      case _ => value
    }

  def decodeConfig(configuration: Configuration): Configuration = {
    val decodedValues = scala.collection.mutable.Map[String, ConfigValue]()
    def decodeRecursive(key: String, configValue: ConfigValue): Unit =
      configValue.valueType() match {
        case ConfigValueType.STRING =>
          decodedValues += key -> decode(configValue)
        case ConfigValueType.LIST =>
          configValue.asInstanceOf[ConfigList].asScala.zipWithIndex.foreach { case (value, index) =>
            decodeRecursive(key + "." + index, value)
          }
        case ConfigValueType.OBJECT =>
          configValue.asInstanceOf[ConfigObject].forEach { case (subKey, subValue) =>
            decodeRecursive(key + "." + subKey, subValue)
          }
        case _ =>
          decodedValues += key -> configValue
      }
    configuration.entrySet.foreach { case (key, configValue) =>
      decodeRecursive(key, configValue)
    }
    Configuration(decodedValues.foldLeft(configuration.underlying) { case (config, (path, updatedConfig)) =>
      config.withValue(path, updatedConfig)
    })
  }
}
