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

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.{ Path, Paths }
import java.nio.file.StandardOpenOption.{ CREATE_NEW, SYNC, TRUNCATE_EXISTING, WRITE }
import scala.jdk.CollectionConverters._

trait AbstractFilePartWriter[T] extends FilePartWriter[T] {
  protected var currentFile: Option[FileChannel] = None
  protected var fileId: Int = 0

  val outputDir: Path
  val filePrefix: String
  val ext: String

  override def openChannel(): Int = {
    closeChannel()
    currentFile = Some(
      FileChannel.open(
        Paths.get(outputDir.toString, s"$filePrefix-$fileId.$ext"),
        Set(WRITE, TRUNCATE_EXISTING, CREATE_NEW, SYNC).asJava
      )
    )
    fileId += 1
    0
  }

  override def closeChannel(): Unit = currentFile.foreach(_.close())

  override def complete(): Array[File] = {
    closeChannel()
    outputDir.toFile.listFiles((_, name) => name.startsWith(filePrefix))
  }
}
