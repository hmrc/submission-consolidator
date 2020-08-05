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

import java.io.{ BufferedWriter, File, FileWriter }
import java.time.Instant

import cats.effect.Resource.fromAutoCloseable
import cats.effect.{ ContextShift, IO, Resource }
import cats.implicits._
import collector.repositories.FormRepository
import consolidator.IOUtils
import consolidator.repositories.{ ConsolidatorJobData, ConsolidatorJobDataRepository }
import consolidator.services.ConsolidatorService.ProcessFormsResult
import javax.inject.{ Inject, Singleton }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.Configuration
import reactivemongo.bson.BSONObjectID

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class ConsolidatorService @Inject()(
  formRepository: FormRepository,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  config: Configuration
)(implicit ec: ExecutionContext)
    extends IOUtils {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  private val batchSize = config.underlying.getInt("consolidator-job-config.batchSize")

  def doConsolidation(projectId: String): IO[Option[File]] = {
    val startTimestamp = Instant.now()
    (for {
      recentConsolidatorJobData <- liftIO(consolidatorJobDataRepository.findRecentLastObjectId(projectId))
      prevLastObjectId = recentConsolidatorJobData.flatMap(_.lastObjectId)
      formsMetadata <- liftIO(formRepository.getFormsMetadata(projectId, prevLastObjectId))
      processFormResult <- formsMetadata
                            .filter(_.count > 0)
                            .map { metadata =>
                              processForms(projectId, metadata.count, prevLastObjectId, metadata.maxId)
                                .map(Some(_))
                            }
                            .getOrElse(IO.pure(None))
      consolidatorJobData = ConsolidatorJobData(
        projectId,
        startTimestamp,
        Instant.now(),
        processFormResult.flatMap(_.lastObjectId),
        None)
      _ <- liftIO(consolidatorJobDataRepository.add(consolidatorJobData))
    } yield processFormResult.map(_.file)).recoverWith {
      case e =>
        logger.error("Failed doConsolidation", e)
        val consolidatorJobData =
          ConsolidatorJobData(projectId, startTimestamp, Instant.now(), None, Some(e.getMessage))
        liftIO(consolidatorJobDataRepository.add(consolidatorJobData)).flatMap(_ => IO.raiseError(e))
    }
  }

  private def processForms(
    projectId: String,
    count: Int,
    afterObjectId: Option[BSONObjectID],
    maxId: BSONObjectID
  ): IO[ProcessFormsResult] =
    for {
      outputFile <- createOutputFile(projectId)
      outputResource = fromAutoCloseable(IO(new BufferedWriter(new FileWriter(outputFile))))
      lastObjectId <- outputResource.use(writeFormsToFile(projectId, count, afterObjectId, maxId, _))
    } yield ProcessFormsResult(lastObjectId, outputFile)

  private def writeFormsToFile(
    projectId: String,
    count: Int,
    afterObjectId: Option[BSONObjectID],
    maxId: BSONObjectID,
    outputFileWriter: BufferedWriter
  ) = {
    val numberOfBatches = count / batchSize + (if (count % batchSize == 0) 0 else 1)
    logger.info(s"Total number of forms to process $count")
    logger.info(s"Max id $maxId")
    logger.info(s"Consolidating in $numberOfBatches batches")
    case class Accumulator(afterObjectId: Option[BSONObjectID], writer: BufferedWriter)
    val initAccIO: IO[Accumulator] =
      liftIO(Future.successful(Either.right(Accumulator(afterObjectId, outputFileWriter))))
    (0 until numberOfBatches)
      .foldLeft(initAccIO) { (accIO, batchNum) =>
        logger.info(s"Consolidating batch $batchNum")
        accIO.flatMap { acc =>
          liftIO(formRepository.getForms(projectId, batchSize)(acc.afterObjectId, Some(maxId)))
            .map { forms =>
              forms.foreach { form =>
                acc.writer.write(form.toJsonLine() + System.lineSeparator())
              }
              acc.writer.flush()
              Accumulator(Some(forms.last.id), acc.writer)
            }
        }
      }
      .map(_.afterObjectId)
  }

  private def createOutputFile(projectId: String) = IO {
    val baseDir = new File(System.getProperty("java.io.tmpdir") + "/submission-consolidator/")
    baseDir.mkdir()
    File.createTempFile(s"$projectId-", ".txt", baseDir)
  }
}

object ConsolidatorService {
  case class ProcessFormsResult(lastObjectId: Option[BSONObjectID], file: File)
}
