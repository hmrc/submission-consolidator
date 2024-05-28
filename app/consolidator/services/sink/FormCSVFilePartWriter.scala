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

package consolidator.services.sink

import java.nio.file.Path

import org.apache.pekko.util.ByteString
import collector.repositories.Form
import org.apache.commons.text.StringEscapeUtils

class FormCSVFilePartWriter(
  override val outputDir: Path,
  override val filePrefix: String,
  maxBytesPerFile: Long,
  headers: List[String]
) extends AbstractFilePartWriter[Form] {

  override val ext: String = "csv"

  override def openChannel(): Int = {
    super.openChannel()
    writeHeader()
  }

  override def write(form: Form): Int = {
    val byteString = ByteString(FormCSVFilePartWriter.toCSV(form, headers) + "\n")
    val currentFileSize: Long = currentFile.map(_.size()).getOrElse(0)
    if (currentFileSize + byteString.size > maxBytesPerFile)
      openChannel()
    currentFile.map(_.write(byteString.toByteBuffer)).getOrElse(0)
  }

  private def writeHeader(): Int =
    currentFile.map(_.write(ByteString(FormCSVFilePartWriter.toCSV(headers) + "\n").toByteBuffer)).getOrElse(0)
}

object FormCSVFilePartWriter {

  def toCSV(headers: List[String]): String = headers.map(StringEscapeUtils.escapeCsv).mkString(",")

  def toCSV(form: Form, headers: List[String]): String =
    headers
      .map(h => form.formData.find(_.id == h).map(f => StringEscapeUtils.escapeCsv(f.value)).getOrElse(""))
      .mkString(",")
}
