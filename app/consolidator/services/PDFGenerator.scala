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

package consolidator.services

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneId }

import org.apache.pekko.util.ByteString
import javax.xml.parsers.DocumentBuilderFactory
import org.xhtmlrenderer.resource.FSEntityResolver

object PDFGenerator {

  private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def generateIFormPdf(projectId: String)(implicit now: Instant): ByteString = {
    val html =
      s"""
         |<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
         |<html xmlns="http://www.w3.org/1999/xhtml">
         |<head>
         |<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
         |<title>$projectId Report</title>
         |</head>
         |<body>
         |<div>
         |	<h4>Title</h4>
         | $projectId Report
         |</div>
         |<div>
         |	<h4>Date</h4>
         | ${DATE_TIME_FORMAT.format(now.atZone(ZoneId.systemDefault()))}
         |</div>
         |</body>
         |</html>
         |""".stripMargin
    val builder = DocumentBuilderFactory.newInstance.newDocumentBuilder
    builder.setEntityResolver(FSEntityResolver.instance)
    val doc = builder.parse(new ByteArrayInputStream(html.getBytes))
    import org.xhtmlrenderer.pdf.ITextRenderer
    val renderer = new ITextRenderer
    renderer.getSharedContext.setMedia("pdf")
    renderer.setDocument(doc, "iform.pdf")
    renderer.layout()
    val bos = new ByteArrayOutputStream();
    renderer.createPDF(bos)
    ByteString(bos.toByteArray)
  }
}
