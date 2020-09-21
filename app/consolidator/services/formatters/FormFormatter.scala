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

package consolidator.services.formatters

import java.time.Instant

import collector.repositories.{ Form, FormField }
import collector.repositories.Form.DATE_TIME_FORMATTER
import org.apache.commons.text.StringEscapeUtils
import play.api.libs.json.{ JsString, Writes, __ }
import play.api.libs.functional.syntax._

trait FormFormatter {
  def format(form: Form): String
}

class CSVFormatter(headers: List[String]) extends FormFormatter {
  override def format(form: Form): String =
    headers
      .map(h => form.formData.find(_.id == h).map(f => StringEscapeUtils.escapeCsv(f.value)).getOrElse(""))
      .mkString(",")
}

object CSVFormatter {
  def apply(headers: List[String]) = new CSVFormatter(headers)
}

object JSONLineFormatter extends FormFormatter {

  private val instantJsonLineWrites: Writes[Instant] = (instant: Instant) =>
    JsString(DATE_TIME_FORMATTER.format(instant))
  private val formJsonLineWrites: Writes[Form] = (
    (__ \ "submissionRef").write[String] and
      (__ \ "projectId").write[String] and
      (__ \ "templateId").write[String] and
      (__ \ "customerId").write[String] and
      (__ \ "submissionTimestamp").write[Instant](instantJsonLineWrites) and
      (__ \ "formData").write[Seq[FormField]]
  )(f => (f.submissionRef, f.projectId, f.templateId, f.customerId, f.submissionTimestamp, f.formData))

  override def format(form: Form): String = formJsonLineWrites.writes(form).toString()
}
