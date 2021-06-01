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

package consolidator.services.sink

import java.nio.file.Path
import java.time.Instant

import akka.util.ByteString
import collector.repositories.Form.DATE_TIME_FORMATTER
import collector.repositories.{ Form, FormField }
import play.api.libs.json.{ JsString, Writes, __ }
import play.api.libs.functional.syntax._

class FormJsonLineFilePartWriter(
  override val outputDir: Path,
  override val filePrefix: String,
  maybeMaxBytesPerFile: Option[Long])
    extends AbstractFilePartWriter[Form] {

  override val ext: String = "txt"

  override def write(form: Form): Int = {
    val line: String = FormJsonLineFilePartWriter.toJson(form)
    val byteString = ByteString(line + "\n")
    val currentFileSize: Long = currentFile.map(_.size()).getOrElse(0)
    if (maybeMaxBytesPerFile.exists(maxBytesPerFile => currentFileSize + byteString.size > maxBytesPerFile))
      openChannel()
    currentFile.map(_.write(byteString.toByteBuffer)).getOrElse(0)
  }
}

object FormJsonLineFilePartWriter {
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

  def toJson(form: Form): String = formJsonLineWrites.writes(form).toString()
}
