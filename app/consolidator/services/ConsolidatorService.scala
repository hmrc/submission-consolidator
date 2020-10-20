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
import consolidator.scheduler.UntilTime
import consolidator.services.ConsolidatorService.ConsolidationResult
import consolidator.services.FilePartOutputStage.Record
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
  config: Configuration
)(implicit ec: ExecutionContext, system: ActorSystem)
    extends IOUtils with FileUploadSettings {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  private val batchSize = config.underlying.getInt("consolidator-job-config.batchSize")

  def doConsolidation(outputPath: Path, params: FormConsolidatorParams)(
    implicit
    time: Time[Instant]): IO[Option[ConsolidationResult]] =
    for {
      afterObjectId <- getAfterObjectId(params)
      untilInstant = getUntilInstant(params, time.now())
      consolidationResult <- processForms(params, afterObjectId, untilInstant, outputPath)
    } yield consolidationResult

  private def processForms(
    params: FormConsolidatorParams,
    afterObjectId: Option[BSONObjectID],
    untilInstant: Instant,
    outputPath: Path
  ): IO[Option[ConsolidationResult]] =
    for {
      formatter <- formFormatterFactory(params.format, params.projectId, afterObjectId)
      filePartOutputStageResult <- writeFormsToFiles(
                                    params.projectId,
                                    afterObjectId,
                                    untilInstant,
                                    outputPath,
                                    formatter)
    } yield
      filePartOutputStageResult
        .map(f => ConsolidationResult(f.lastObjectId, f.count, f.reportFiles.toList))

  private def writeFormsToFiles(
    projectId: String,
    afterObjectId: Option[BSONObjectID],
    untilInstant: Instant,
    outputPath: Path,
    formatter: FormFormatter
  ) =
    liftIO(
      formRepository
        .formsSource(projectId, batchSize, untilInstant, afterObjectId)
        .map { form =>
          Record(formatter.formLine(form), form.id)
        }
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

  private def getAfterObjectId(params: FormConsolidatorParams) =
    params match {
      case _: ScheduledFormConsolidatorParams =>
        for {
          recentConsolidatorJobData <- liftIO(consolidatorJobDataRepository.findRecentLastObjectId(params.projectId))
          lastObjectId = recentConsolidatorJobData.flatMap(_.lastObjectId)
        } yield lastObjectId
      case u: ManualFormConsolidatorParams =>
        IO.pure(
          Option(BSONObjectID.fromTime(u.startInstant.toEpochMilli, true))
        )
    }

  private def getUntilInstant(params: FormConsolidatorParams, currentInstant: Instant) =
    params match {
      case s: ScheduledFormConsolidatorParams =>
        val bufferSeconds = 5
        s.untilTime match {
          case UntilTime.now => currentInstant.atZone(ZoneId.systemDefault()).minusSeconds(bufferSeconds).toInstant
          case UntilTime.`previous_day` =>
            currentInstant
              .atZone(ZoneId.systemDefault())
              .minusDays(1)
              .withHour(23)
              .withMinute(59)
              .withSecond(59)
              .withNano(0)
              .toInstant
        }
      case u: ManualFormConsolidatorParams =>
        u.endInstant
    }

}

object ConsolidatorService {
  case class ConsolidationResult(lastObjectId: BSONObjectID, count: Int, reportFiles: List[File])
}
