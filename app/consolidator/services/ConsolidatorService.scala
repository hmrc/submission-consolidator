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

import java.nio.file.Path
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.{ ContextShift, IO }
import collector.repositories.FormRepository
import common.Time
import consolidator.IOUtils
import consolidator.repositories.ConsolidatorJobDataRepository
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.FilePartOutputStage.{ ByteStringObjectId, FileOutputResult }
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import reactivemongo.bson.BSONObjectID

import scala.concurrent.ExecutionContext

@Singleton
class ConsolidatorService @Inject()(
  formRepository: FormRepository,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  config: Configuration
)(implicit ec: ExecutionContext, system: ActorSystem)
    extends IOUtils with FileUploadSettings {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  private val batchSize = config.underlying.getInt("consolidator-job-config.batchSize")
  private val CREATION_TIME_BUFFER_SECONDS = 5

  def doConsolidation(projectId: String, outputPath: Path)(implicit time: Time[Instant]): IO[ConsolidationResult] =
    for {
      recentConsolidatorJobData <- liftIO(consolidatorJobDataRepository.findRecentLastObjectId(projectId))
      prevLastObjectId = recentConsolidatorJobData.flatMap(_.lastObjectId)
      consolidationResult <- processForms(projectId, prevLastObjectId, outputPath)
    } yield consolidationResult

  private def processForms(projectId: String, afterObjectId: Option[BSONObjectID], outputPath: Path)(
    implicit
    time: Time[Instant]): IO[ConsolidationResult] =
    for {
      fileOutputResult <- writeFormsToFiles(projectId, afterObjectId, outputPath)
    } yield
      if (fileOutputResult.isEmpty)
        ConsolidationResult(outputPath = outputPath)
      else
        ConsolidationResult(
          lastObjectId = fileOutputResult.map(_.lastObjectId),
          count = fileOutputResult.map(_.count).getOrElse(0),
          outputPath = outputPath
        )

  private def writeFormsToFiles(projectId: String, afterObjectId: Option[BSONObjectID], outputPath: Path)(
    implicit
    time: Time[Instant]): IO[Option[FileOutputResult]] =
    liftIO {
      val creationTime = time.now().minusSeconds(CREATION_TIME_BUFFER_SECONDS)
      formRepository
        .formsSource(projectId, batchSize, creationTime, afterObjectId)
        .map { form =>
          ByteStringObjectId(
            form.id,
            ByteString(form.toJsonLine())
          )
        }
        .runWith(
          Sink.fromGraph(
            new FilePartOutputStage(
              outputPath,
              "report",
              reportPerFileSizeInBytes,
              projectId,
              batchSize
            )
          )
        )
        .map { fileOutputResult =>
          Right(fileOutputResult)
        }
        .recover {
          case e => Left(e)
        }
    }
}

object ConsolidatorService {
  case class ConsolidationResult(lastObjectId: Option[BSONObjectID] = None, count: Int = 0, outputPath: Path)
}
