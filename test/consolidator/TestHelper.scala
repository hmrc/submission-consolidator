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

package consolidator

import java.io.{ BufferedWriter, File, FileInputStream, FileWriter }
import java.nio.file.{ Files, Path, Paths }
import collector.repositories.Form
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import scala.jdk.CollectionConverters._

object TestHelper {

  def tmpDir(dirPrefix: String): Path =
    Files.createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/$dirPrefix${System.currentTimeMillis()}")
    )

  def formsRows(forms: List[Form]): List[List[String]] = {
    val headers = forms.flatMap(_.formData.map(_.id)).distinct.sorted
    List(headers) ++ forms.map(form => headers.map(h => form.formData.find(_.id == h).map(_.value).getOrElse("")))
  }

  def excelFileRows(file: File): List[List[String]] = {
    val excelFile = new FileInputStream(file)
    val workbook = new XSSFWorkbook(excelFile)
    val rows =
      workbook.getSheetAt(0).iterator().asScala.map(_.cellIterator().asScala.map(_.getStringCellValue).toList).toList
    workbook.close()
    excelFile.close()
    rows
  }

  def fileRows(file: File): List[String] = {
    val source = scala.io.Source.fromFile(file)
    val lines = source.getLines().toList
    source.close()
    lines
  }

  def createFileInDir(dir: Path, fileName: String, size: Int): File = {
    val file = Files.createFile(Paths.get(s"$dir/$fileName")).toFile
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("a" * size)
    bw.close()
    file
  }

  def createTmpDir(prefix: String): Path =
    Files.createDirectories(
      Paths.get(System.getProperty("java.io.tmpdir") + s"/$prefix-${System.currentTimeMillis()}")
    )
}
