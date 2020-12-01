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

package consolidator.services.sink

import java.io.FileOutputStream
import java.nio.file.{ Path, Paths }

import collector.repositories.Form
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import scala.util.Try

class FormExcelFilePartWriter(
  override val outputDir: Path,
  override val filePrefix: String,
  maxBytesPerFile: Long,
  headers: List[String]
) extends AbstractFilePartWriter[Form] {

  override val ext: String = "xlsx"

  private var currentWorkbook: Option[XSSFWorkbook] = None
  private var currentSheetByteCount = 0
  private var rowNum = 0

  override def openChannel(): Int = {
    closeChannel()
    currentWorkbook = Some(new XSSFWorkbook())
    val headersByteSize: Int = writeHeaders()
    currentSheetByteCount += headersByteSize
    headersByteSize
  }

  private def writeHeaders() = {
    writeRow(headers)
    headers.mkString("").getBytes("UTF-8").length
  }

  override def write(form: Form): Int = {
    val formValues = headers.flatMap(h => form.formData.find(_.id == h).map(_.value))
    val formValuesByteCount = formValues.mkString("").getBytes("UTF-8").length
    if (currentSheetByteCount + formValuesByteCount > maxBytesPerFile)
      openChannel()
    writeRow(formValues)
    currentSheetByteCount += formValuesByteCount
    formValuesByteCount
  }

  override def closeChannel(): Unit =
    currentWorkbook.foreach { workbook =>
      val fileOutputStream = new FileOutputStream(Paths.get(outputDir.toString, s"$filePrefix-$fileId.$ext").toFile)
      try {
        workbook.write(fileOutputStream)
        rowNum = 0
        currentSheetByteCount = 0
      } finally {
        Try(workbook.close())
        Try(fileOutputStream.close())
      }
      fileId += 1
    }

  private def writeRow(cells: List[String]): Unit =
    currentWorkbook.foreach { workbook =>
      var sheet = workbook.getSheet("Forms")
      if (sheet == null) {
        sheet = workbook.createSheet("Forms")
      }
      val row = sheet.createRow(rowNum)
      cells.zipWithIndex.foreach {
        case (c, i) => row.createCell(i).setCellValue(c)
      }
      rowNum += 1
    }
}
