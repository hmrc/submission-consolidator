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

import cats.effect.IO
import collector.repositories.Form.DATE_TIME_FORMATTER
import collector.repositories.{ Form, FormField, FormRepository }
import consolidator.IOUtils
import consolidator.services.formatters.ConsolidationFormat.ConsolidationFormat
import javax.inject.{ Inject, Singleton }
import org.apache.commons.text.StringEscapeUtils
import play.api.libs.functional.syntax._
import play.api.libs.json.{ JsString, Writes, __ }
import reactivemongo.bson.BSONObjectID

import scala.concurrent.ExecutionContext

trait FormFormatter {

  def headerLine: Option[String]

  def formLine(form: Form): String

  def ext: String
}

case class CSVFormatter(headers: List[String]) extends FormFormatter {

  override def headerLine = Some(headers.map(StringEscapeUtils.escapeCsv).mkString(","))

  override def formLine(form: Form): String =
    headers
      .map(h => form.formData.find(_.id == h).map(f => StringEscapeUtils.escapeCsv(f.value)).getOrElse(""))
      .mkString(",")

  override def ext: String = "xls"
}

case object JSONLineFormatter extends FormFormatter {

  override def headerLine: Option[String] = None

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

  override def formLine(form: Form): String = formJsonLineWrites.writes(form).toString()

  override def ext: String = "xls"
}

@Singleton
class FormFormatterFactory @Inject()(formRepository: FormRepository)(implicit ec: ExecutionContext) extends IOUtils {
  def apply(
    consolidationFormat: ConsolidationFormat,
    projectId: String,
    afterObjectId: Option[BSONObjectID]
  ): IO[FormFormatter] =
    consolidationFormat match {
      case ConsolidationFormat.csv =>
        for {
          formDataIds <- liftIO(formRepository.distinctFormDataIds(projectId, afterObjectId))
        } yield CSVFormatter(formDataIds)
      case ConsolidationFormat.jsonl => IO.pure(JSONLineFormatter)
    }
}
