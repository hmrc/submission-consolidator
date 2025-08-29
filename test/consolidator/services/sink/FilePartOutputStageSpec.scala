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
import java.nio.file.StandardOpenOption.{ CREATE_NEW, SYNC, TRUNCATE_EXISTING, WRITE }
import java.nio.file.{ Path, Paths }
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.util.ByteString
import collector.repositories.DataGenerators
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import consolidator.TestHelper._
import consolidator.services.sink.FilePartOutputStage.IOOperationIncompleteException
import org.apache.commons.text.StringEscapeUtils
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

class FilePartOutputStageSpec
    extends AnyWordSpec with DataGenerators with ScalaFutures with Matchers with DiffShouldMatcher
    with ArgumentMatchersSugar with IdiomaticMockito {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(30, Seconds), Span(1, Millis))

  implicit val system: ActorSystem = ActorSystem("FilePartOutputStageSpec")

  case class Record(id: String)

  trait TestFixture {
    val reportDir: Path = tmpDir("FilePartOutputStageSpec")
    lazy val noOfRecords = 10
    lazy val records: List[Record] = (1 to noOfRecords)
      .map(i => Record(s"value$i"))
      .toList
    lazy val recordsSource = Source(records)
    lazy val testFilePartWriter = new TestFilePartWriter(reportDir)
  }

  "FilePartOutputStage operator" should {

    "process source records (source empty)" in new TestFixture {
      override lazy val noOfRecords = 0
      val future = recordsSource
        .runWith(Sink.fromGraph(new FilePartOutputStage[Record]()(testFilePartWriter)))
      whenReady(future) { result =>
        result shouldBe None
      }
    }

    "process source records" in new TestFixture {
      val future = recordsSource
        .runWith(Sink.fromGraph(new FilePartOutputStage[Record]()(testFilePartWriter)))
      whenReady(future) { result =>
        result.isDefined shouldBe true
        result.get.count shouldBe noOfRecords
        result.get.lastValue shouldBe records.last
        result.get.reportFiles shouldNot be(empty)
        result.get.reportFiles.zipWithIndex.foreach { case (file, index) =>
          file.getName shouldBe s"test-$index.ext"
          fileRows(file) shouldMatchTo recordRows(records)
        }
      }
    }

    "handle and report failure when file part writer fails" in new TestFixture {
      val mockFilePartWriter = mock[FilePartWriter[Record]]
      mockFilePartWriter.openChannel() throws new RuntimeException("failed to open channel")
      mockFilePartWriter.closeChannel().doesNothing()
      val future = recordsSource
        .runWith(Sink.fromGraph(new FilePartOutputStage[Record]()(mockFilePartWriter)))

      whenReady(future.failed) { exception =>
        exception shouldBe a[IOOperationIncompleteException]
        exception.getMessage shouldBe "IO operation was stopped unexpectedly because of java.lang.RuntimeException: failed to open channel"

        mockFilePartWriter.closeChannel() wasCalled once
      }
    }
  }

  def recordRows(records: List[Record]): List[String] =
    records.map(r => StringEscapeUtils.escapeCsv(r.id))

  class TestFilePartWriter(outputDir: Path) extends FilePartWriter[Record] {
    private var currentFile: Option[FileChannel] = None
    private var fileId: Int = 0

    override def openChannel(): Int = {
      closeChannel()
      currentFile = Some(
        FileChannel.open(
          Paths.get(outputDir.toString, s"test-$fileId.ext"),
          Set(WRITE, TRUNCATE_EXISTING, CREATE_NEW, SYNC).asJava
        )
      )
      fileId += 1
      0
    }

    override def write(value: Record): Int =
      currentFile.map(_.write(ByteString(StringEscapeUtils.escapeCsv(value.id) + "\n").toByteBuffer)).getOrElse(0)

    override def closeChannel(): Unit = currentFile.foreach(_.close())

    override def complete(): Array[File] = {
      closeChannel()
      outputDir.toFile.listFiles()
    }
  }
}
