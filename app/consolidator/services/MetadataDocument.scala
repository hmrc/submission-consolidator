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

package consolidator.services

import scala.xml.Utility

case class MetadataDocument(documents: Documents) {
  val xmlDec = """<?xml version="1.0" encoding="UTF-8" standalone="no"?>"""
  def toXml = xmlDec + Utility.trim(documents.toXml)
}

case class Documents(document: Document) {
  def toXml =
    <documents xmlns="http://govtalk.gov.uk/hmrc/gis/content/1">
      {
      document.toXml
    }
    </documents>
}
case class Document(header: Header, metadata: Metadata) {
  def toXml =
    <document>
    {
      header.toXml
    }
    {
      metadata.toXml
    }
  </document>
}
case class Header(
  title: String,
  format: String,
  mimeType: String,
  store: Boolean,
  source: String,
  target: String,
  reconciliationId: String
) {
  def toXml =
    <header>
      <title>{title}</title>
      <format>{format}</format>
      <mime_type>{mimeType}</mime_type>
      <store>{store}</store>
      <source>{source}</source>
      <target>{target}</target>
      <reconciliation_id>{reconciliationId}</reconciliation_id>
    </header>
}
case class Metadata(attributes: List[Attribute]) {
  def toXml =
    <metadata>
      {
      attributes.map { a =>
        <attribute>
            <attribute_name>{a.name}</attribute_name>
            <attribute_type>{a.`type`}</attribute_type>
            <attribute_values>
              {
          a.values.map { av =>
            <attribute_value>{av}</attribute_value>
          }
        }
            </attribute_values>
          </attribute>
      }
    }
    </metadata>
}
case class Attribute(name: String, `type`: String, values: List[String])
