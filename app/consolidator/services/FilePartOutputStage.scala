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

package consolidator.services

import java.nio.channels.FileChannel
import java.nio.file.{ OpenOption, Path, Paths }
import java.nio.file.StandardOpenOption.{ CREATE_NEW, SYNC, TRUNCATE_EXISTING, WRITE }

import akka.stream.{ AbruptStageTerminationException, Attributes, Inlet, SinkShape }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler }
import akka.util.ByteString
import reactivemongo.bson.BSONObjectID

import scala.concurrent.{ Future, Promise }
import scala.util.Success
import scala.util.control.NonFatal
import akka.util.ccompat.JavaConverters._
import consolidator.services.FilePartOutputStage.{ ByteStringObjectId, FileOutputResult }
import org.slf4j.{ Logger, LoggerFactory }

class FilePartOutputStage(
  baseDir: Path,
  filePrefix: String,
  maxTotalBytes: Long,
  maxBytesPerFile: Long,
  projectId: String,
  batchSize: Int,
  options: Set[OpenOption] = Set(WRITE, TRUNCATE_EXISTING, CREATE_NEW, SYNC)
) extends GraphStageWithMaterializedValue[
      SinkShape[ByteStringObjectId],
      Future[
        Option[FileOutputResult]
      ]] {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val in: Inlet[ByteStringObjectId] = Inlet("FilePartOutputStageSink")
  override val shape: SinkShape[ByteStringObjectId] = SinkShape(in)

  final class IOOperationIncompleteException(message: String, cause: Throwable)
      extends RuntimeException(message, cause) {

    def this(cause: Throwable) =
      this(s"IO operation was stopped unexpectedly because of $cause", cause)

  }

  override def createLogicAndMaterializedValue(
    inheritedAttributes: Attributes
  ): (GraphStageLogic, Future[Option[FileOutputResult]]) = {
    val mat = Promise[Option[FileOutputResult]]()
    val logic: GraphStageLogic with InHandler = new GraphStageLogic(shape) with InHandler {
      private var currentFileChanel: FileChannel = _
      private var lastByteStringWithId: ByteStringObjectId = _
      private var fileId: Int = 0
      private var byteCount: Long = 0
      private var dataCount: Int = 0

      override def preStart(): Unit =
        try {
          setNewFileChannel()
          pull(in)
        } catch {
          case NonFatal(t) =>
            closeFile(Some(new IOOperationIncompleteException(t)))
            failStage(t)
        }

      private def setNewFileChannel() = {
        val path = Paths.get(baseDir.toString, s"$filePrefix-$fileId.txt")
        currentFileChanel = FileChannel.open(
          path,
          options.asJava
        )
        fileId += 1
      }

      override def onPush(): Unit = {
        val next = grab(in)
        try {
          val nextData = next.byteString ++ ByteString("\n")
          val nextDataSize = nextData.size

          if (byteCount + nextDataSize > maxTotalBytes) {
            closeFile(None)
            completeStage()
          } else {
            if (currentFileChanel.size() + nextDataSize > maxBytesPerFile) {
              setNewFileChannel()
            }
            byteCount += currentFileChanel.write(nextData.toByteBuffer)
            if (dataCount % batchSize == 0) {
              logger.info(s"Processing batch ${(dataCount / 500) + 1} for project $projectId")
            }
            dataCount += 1
            lastByteStringWithId = next
            pull(in)
          }
        } catch {
          case NonFatal(t) =>
            closeFile(Some(new IOOperationIncompleteException(t)))
            failStage(t)
        }
      }

      override def onUpstreamFailure(t: Throwable): Unit = {
        closeFile(Some(new IOOperationIncompleteException(t)))
        failStage(t)
      }

      override def onUpstreamFinish(): Unit = {
        closeFile(None)
        completeStage()
      }

      override def postStop(): Unit =
        if (!mat.isCompleted) {
          val failure = new AbruptStageTerminationException(this)
          closeFile(Some(failure))
          mat.tryFailure(failure)
        }

      private def closeFile(failed: Option[Throwable]): Unit =
        try {
          currentFileChanel.close()
          failed match {
            case Some(t) => mat.tryFailure(t)
            case None =>
              mat.tryComplete(
                Success(if (dataCount == 0) None else Some(FileOutputResult(lastByteStringWithId.id, dataCount))))
          }
        } catch {
          case NonFatal(t) =>
            mat.tryFailure(failed.getOrElse(t))
        }

      setHandler(in, this)
    }
    (logic, mat.future)
  }
}

object FilePartOutputStage {
  case class FileOutputResult(lastObjectId: BSONObjectID, count: Int)
  case class ByteStringObjectId(id: BSONObjectID, byteString: ByteString)
}
