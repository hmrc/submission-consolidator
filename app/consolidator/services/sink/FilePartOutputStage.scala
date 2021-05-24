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

import java.io.File

import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler }
import akka.stream.{ AbruptStageTerminationException, Attributes, Inlet, SinkShape }
import consolidator.services.sink.FilePartOutputStage.{ FilePartOutputStageResult, IOOperationIncompleteException }

import scala.concurrent.{ Future, Promise }
import scala.util.Success
import scala.util.control.NonFatal

class FilePartOutputStage[T: FilePartWriter]()
    extends GraphStageWithMaterializedValue[
      SinkShape[T],
      Future[
        Option[FilePartOutputStageResult[T]]
      ]] {

  val in: Inlet[T] = Inlet("FilePartOutputStageSink")
  override val shape: SinkShape[T] = SinkShape(in)

  override def createLogicAndMaterializedValue(
    inheritedAttributes: Attributes
  ): (GraphStageLogic, Future[Option[FilePartOutputStageResult[T]]]) = {
    val mat = Promise[Option[FilePartOutputStageResult[T]]]()
    val logic: GraphStageLogic with InHandler = new GraphStageLogic(shape) with InHandler {
      private var lastValue: T = _
      private var dataCount: Int = 0

      override def preStart(): Unit =
        try {
          FilePartWriter[T].openChannel()
          pull(in)
        } catch {
          case NonFatal(t) =>
            close(Some(new IOOperationIncompleteException(t)))
            failStage(t)
        }

      override def onPush(): Unit = {
        val nextValue = grab(in)
        try {
          FilePartWriter[T].write(nextValue)
          dataCount += 1
          lastValue = nextValue
          pull(in)
        } catch {
          case NonFatal(t) =>
            close(Some(new IOOperationIncompleteException(t)))
            failStage(t)
        }
      }

      override def onUpstreamFailure(t: Throwable): Unit = {
        close(Some(new IOOperationIncompleteException(t)))
        failStage(t)
      }

      override def onUpstreamFinish(): Unit = {
        close(None)
        completeStage()
      }

      override def postStop(): Unit =
        if (!mat.isCompleted) {
          val failure = new AbruptStageTerminationException(this)
          close(Some(failure))
          ()
        }

      private def close(failed: Option[Throwable]): Unit =
        try {
          failed match {
            case Some(t) =>
              FilePartWriter[T].closeChannel()
              mat.tryFailure(t)
            case None =>
              mat.tryComplete(
                Success(
                  if (dataCount == 0) None
                  else
                    Some(
                      FilePartOutputStageResult(
                        lastValue,
                        dataCount,
                        FilePartWriter[T].complete()
                      )
                    )
                )
              )
          }
          ()
        } catch {
          case NonFatal(t) =>
            mat.tryFailure(failed.getOrElse(t))
            ()
        }

      setHandler(in, this)
    }
    (logic, mat.future)
  }
}

object FilePartOutputStage {

  final class IOOperationIncompleteException(message: String, cause: Throwable)
      extends RuntimeException(message, cause) {

    def this(cause: Throwable) =
      this(s"IO operation was stopped unexpectedly because of $cause", cause)
  }

  case class FilePartOutputStageResult[T](lastValue: T, count: Int, reportFiles: Array[File])
}
