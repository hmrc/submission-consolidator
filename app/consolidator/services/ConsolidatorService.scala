/*
 * Copyright 2022 HM Revenue & Customs
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
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import cats.effect.{ ContextShift, IO }
import collector.repositories.{ Form, FormRepository }
import common.Time
import consolidator.IOUtils
import consolidator.repositories.ConsolidatorJobDataRepository
import consolidator.services.ConsolidatorService.ConsolidationResult
import ConsolidationFormat.ConsolidationFormat
import consolidator.services.sink.{ FilePartOutputStage, FormCSVFilePartWriter, FormExcelFilePartWriter, FormJsonLineFilePartWriter }
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

  def doConsolidation(outputPath: Path, params: FormConsolidatorParams)(
    implicit
    time: Time[Instant]): IO[Option[ConsolidationResult]] =
    for {
      afterObjectId <- getAfterObjectId(params)
      untilInstant = params.getUntilInstant(time.now())
      consolidationResult <- processForms(params, afterObjectId, untilInstant, outputPath)
    } yield consolidationResult

  private def processForms(
    params: FormConsolidatorParams,
    afterObjectId: Option[BSONObjectID],
    untilInstant: Instant,
    outputPath: Path
  ): IO[Option[ConsolidationResult]] =
    for {
      formDataIds <- formDataIds(params.projectId, afterObjectId, params.format)
      filePartOutputStageResult <- writeFormsToFiles(
                                    params.projectId,
                                    afterObjectId,
                                    untilInstant,
                                    formDataIds,
                                    outputPath,
                                    params.format)
    } yield
      filePartOutputStageResult
        .map(f => ConsolidationResult(f.lastValue.id, f.count, f.reportFiles.toList))

  private def writeFormsToFiles(
    projectId: String,
    afterObjectId: Option[BSONObjectID],
    untilInstant: Instant,
    formDataIds: List[String],
    outputDir: Path,
    format: ConsolidationFormat) =
    liftIO(
      formRepository
        .formsSource(projectId, batchSize, afterObjectId, untilInstant)
        .runWith(
          Sink.fromGraph(
            new FilePartOutputStage[Form]()(format match {
              case ConsolidationFormat.jsonl =>
                new FormJsonLineFilePartWriter(outputDir, "report", reportPerFileSizeInBytes)
              case ConsolidationFormat.csv =>
                new FormCSVFilePartWriter(outputDir, "report", reportPerFileSizeInBytes, formDataIds)
              case ConsolidationFormat.xlsx =>
                new FormExcelFilePartWriter(outputDir, "report", reportPerFileSizeInBytes, formDataIds)
            })
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

  private def formDataIds(
    projectId: String,
    afterObjectId: Option[BSONObjectID],
    format: ConsolidationFormat): IO[List[String]] =
    format match {
      case ConsolidationFormat.csv | ConsolidationFormat.`xlsx` =>
        liftIO(formRepository.distinctFormDataIds(projectId, afterObjectId))
      case ConsolidationFormat.jsonl => IO.pure(List.empty)
    }
}

object ConsolidatorService {
  case class ConsolidationResult(lastObjectId: BSONObjectID, count: Int, reportFiles: List[File])
}
