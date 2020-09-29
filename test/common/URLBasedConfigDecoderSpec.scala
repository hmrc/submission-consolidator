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

package common

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

class URLBasedConfigDecoderSpec extends AnyWordSpec with Matchers with URLBasedConfigDecoder {

  "decodeConfig" should {

    "decode string values that start with urlenc:" in {
      val config = buildConfig("urlenc:1%202")
      val expected = buildConfig("1 2")
      decodeConfig(config) shouldBe expected
    }

    "decode string values that start with urlenc:, from nested object" in {
      val config = Configuration.apply(ConfigFactory.parseString(s"""
                                                                    |someKey {
                                                                    |  someNestedKey = "urlenc:1%202"
                                                                    |}
                                                                    |""".stripMargin))
      val expected = Configuration.apply(ConfigFactory.parseString(s"""
                                                                      |someKey {
                                                                      |  someNestedKey = "1 2"
                                                                      |}
                                                                      |""".stripMargin))
      decodeConfig(config) shouldBe expected
    }

    "decode string values that start with urlenc:, from nested array" in {
      val config = Configuration.apply(ConfigFactory.parseString(s"""
                                                                    |someKey = [{
                                                                    |  someNestedKey = "urlenc:1%202"
                                                                    |}]
                                                                    |""".stripMargin))
      val expected = Configuration.apply(ConfigFactory.parseString(s"""
                                                                      |someKey = {
                                                                      |  "0": {
                                                                      |    someNestedKey = "1 2"
                                                                      |  }
                                                                      |}
                                                                      |""".stripMargin))

      decodeConfig(config) shouldBe expected
    }

    "not decode strings that do not start with urlenc:" in {
      val config = buildConfig("1 2")
      decodeConfig(config) shouldBe config
    }

    "ignore non-string values" in {
      val config = buildConfig(1)
      decodeConfig(config) shouldBe config
    }

    "retains null values" in {
      val config = buildConfig(null)
      decodeConfig(config) shouldBe config
    }
  }

  def buildConfig(value: Any) =
    Configuration.apply(ConfigFactory.parseString(s"""
                                                     |someKey = "$value"
                                                     |""".stripMargin))
}
