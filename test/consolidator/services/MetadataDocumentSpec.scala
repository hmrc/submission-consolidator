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

package consolidator.services

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.xml.Utility

class MetadataDocumentSpec extends AnyWordSpec with Matchers {
  "toXml" should {
    "return the xml for the given Documents" in {
      val documents = Documents(
        Document(
          Header(
            "some-title",
            "some-format",
            "some-mime-type",
            true,
            "some-source",
            "some-target",
            "some-reconcilation-id"
          ),
          Metadata(List(Attribute("some-attribute-1", "some-type-1", List("some-value-1"))))
        )
      )
      val expected = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>""" + Utility.trim(
        <documents xmlns="http://govtalk.gov.uk/hmrc/gis/content/1">
        <document>
          <header>
            <title>some-title</title>
            <format>some-format</format>
            <mime_type>some-mime-type</mime_type>
            <store>true</store>
            <source>some-source</source>
            <target>some-target</target>
            <reconciliation_id>some-reconcilation-id</reconciliation_id>
          </header>
          <metadata>
            <attribute>
              <attribute_name>some-attribute-1</attribute_name>
              <attribute_type>some-type-1</attribute_type>
              <attribute_values>
                <attribute_value>some-value-1</attribute_value>
              </attribute_values>
            </attribute>
          </metadata>
        </document>
      </documents>
      )
      MetadataDocument(documents).toXml shouldBe expected
    }
  }
}
