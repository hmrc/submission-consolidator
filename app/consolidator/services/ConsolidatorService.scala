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

import java.io.File
import java.nio.file.Path
import java.time.{ Instant, ZoneId }

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import cats.effect.{ ContextShift, IO }
import collector.repositories.FormRepository
import common.Time
import consolidator.IOUtils
import consolidator.repositories.ConsolidatorJobDataRepository
import consolidator.scheduler.{ ConsolidatorJobParam, UntilTime }
import consolidator.scheduler.UntilTime.UntilTime
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.FilePartOutputStage.{ FilePartOutputStageResult, Record }
import consolidator.services.formatters.{ FormFormatter, FormFormatterFactory }
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import reactivemongo.bson.BSONObjectID

import scala.concurrent.ExecutionContext

@Singleton
class ConsolidatorService @Inject()(
  formRepository: FormRepository,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  formFormatterFactory: FormFormatterFactory,
  config: Configuration)(implicit ec: ExecutionContext, system: ActorSystem)
    extends IOUtils with FileUploadSettings {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  private val batchSize = config.underlying.getInt("consolidator-job-config.batchSize")
  private val UNTIL_TIME_BUFFER_SECONDS = 5

  def doConsolidation(outputPath: Path, consolidatorJobParam: ConsolidatorJobParam)(
    implicit
    time: Time[Instant]): IO[Option[ConsolidationResult]] =
    for {
      recentConsolidatorJobData <- liftIO(
                                    consolidatorJobDataRepository.findRecentLastObjectId(
                                      consolidatorJobParam.projectId))
      prevLastObjectId = recentConsolidatorJobData.flatMap(_.lastObjectId)
      consolidationResult <- processForms(consolidatorJobParam, prevLastObjectId, outputPath)
    } yield consolidationResult

  private def processForms(
    consolidatorJobParam: ConsolidatorJobParam,
    afterObjectId: Option[BSONObjectID],
    outputPath: Path,
  )(
    implicit
    time: Time[Instant]): IO[Option[ConsolidationResult]] =
    for {
      formatter <- formFormatterFactory(consolidatorJobParam.format, consolidatorJobParam.projectId, afterObjectId)
      filePartOutputStageResult <- writeFormsToFiles(
                                    consolidatorJobParam.projectId,
                                    afterObjectId,
                                    consolidatorJobParam.untilTime,
                                    outputPath,
                                    formatter)
    } yield
      filePartOutputStageResult
        .map(f => ConsolidationResult(f.lastObjectId, f.count, f.reportFiles.toList))

  private def writeFormsToFiles(
    projectId: String,
    afterObjectId: Option[BSONObjectID],
    untilTime: UntilTime,
    outputPath: Path,
    formatter: FormFormatter
  )(implicit time: Time[Instant]): IO[Option[FilePartOutputStageResult]] =
    for {
      filePartOutputStageResult <- processFormsStream(projectId, afterObjectId, untilTime, outputPath, formatter)
    } yield filePartOutputStageResult

  private def processFormsStream(
    projectId: String,
    afterObjectId: Option[BSONObjectID],
    untilTime: UntilTime,
    outputPath: Path,
    formatter: FormFormatter)(
    implicit
    time: Time[Instant]) =
    liftIO(
      formRepository
        .formsSource(projectId, batchSize, calculateUntilTime(untilTime, time.now()), afterObjectId)
        .map(form => {
          Record(formatter.formLine(form), form.id)
        })
        .runWith(
          Sink.fromGraph(
            new FilePartOutputStage(
              outputPath,
              "report",
              formatter.ext,
              reportPerFileSizeInBytes,
              projectId,
              batchSize,
              formatter.headerLine
            )
          )
        )
        .map { filePartOutputStageResult =>
          Right(filePartOutputStageResult)
        }
        .recover {
          case e => Left(e)
        }
    )

  private def calculateUntilTime(untilTime: UntilTime, currentTime: Instant) = untilTime match {
    case UntilTime.now => currentTime.atZone(ZoneId.systemDefault()).minusSeconds(UNTIL_TIME_BUFFER_SECONDS).toInstant
    case UntilTime.`previous_day` =>
      currentTime
        .atZone(ZoneId.systemDefault())
        .minusDays(1)
        .withHour(23)
        .withMinute(59)
        .withSecond(59)
        .withNano(0)
        .toInstant
  }
}

object ConsolidatorService {
  case class ConsolidationResult(lastObjectId: BSONObjectID, count: Int, reportFiles: List[File])
}
