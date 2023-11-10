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

package consolidator.services

import cats.implicits._
import consolidator.connectors.{ ObjectStoreConnector, SdesConnector }
import consolidator.proxies._
import consolidator.repositories.{ ConsolidatorJobDataRepository, CorrelationId, NotificationStatus, SdesReportData, SdesReportsPageData, SdesSubmission, SdesSubmissionRepository }
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.Filters.{ equal, exists }
import org.mongodb.scala.result.DeleteResult
import org.slf4j.{ Logger, LoggerFactory }
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5

import java.time.{ Instant, LocalDateTime }
import java.util.Base64
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

/** @param sdesConnector
  * @param sdesConfig
  * @param sdesSubmissionRepository
  * @param ec
  */
@Singleton
class SdesService @Inject() (
  sdesConnector: SdesConnector,
  sdesConfig: SdesConfig,
  sdesSubmissionRepository: SdesSubmissionRepository,
  consolidatorJobDataRepository: ConsolidatorJobDataRepository,
  objectStoreConnector: ObjectStoreConnector
)(implicit ec: ExecutionContext) {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  def notifySDES(
    envelopeId: String,
    submissionRef: String,
    objWithSummary: ObjectSummaryWithMd5
  ): Future[Either[Exception, Unit]] = {
    val sdesSubmission = SdesSubmission.createSdesSubmission(envelopeId, submissionRef, objWithSummary.contentLength)
    val notifyRequest = createNotifyRequest(objWithSummary, sdesSubmission._id)
    for {
      res <- sdesConnector.notifySDES(notifyRequest).recoverWith { case e =>
               logger.error("Failed to notify SDES for file-ready: ", e)
               Future.failed(e)
             }
      _ <- sdesSubmissionRepository.upsert(sdesSubmission)
    } yield res
  }

  def find(correlationId: CorrelationId): Future[Option[SdesSubmission]] =
    sdesSubmissionRepository.find(correlationId.value)

  def notifySDESById(correlationId: CorrelationId): Future[Unit] =
    for {
      sdesSubmission <- find(correlationId)
      _ <- sdesSubmission match {
             case Some(submission) =>
               for {
                 objSummary <- objectStoreConnector.zipFiles(submission.envelopeId)
                 _ <- objSummary.fold(
                        error =>
                          Future.failed(
                            new RuntimeException(s"Correlation id [$correlationId] $error")
                          ),
                        { objSummary =>
                          val notifyRequest = createNotifyRequest(objSummary, submission._id)
                          sdesConnector.notifySDES(notifyRequest)
                          save(submission.copy(lastUpdated = Some(Instant.now())))
                        }
                      )
               } yield ()
             case None =>
               Future.failed(
                 new RuntimeException(s"Correlation id [$correlationId] not found in mongo collection")
               )
           }
    } yield ()

  private def createNotifyRequest(objSummary: ObjectSummaryWithMd5, correlationId: CorrelationId): SdesNotifyRequest =
    SdesNotifyRequest(
      sdesConfig.informationType,
      FileMetaData(
        sdesConfig.recipientOrSender,
        objSummary.location.fileName,
        s"${sdesConfig.fileLocationUrl}${objSummary.location.asUri}",
        FileChecksum(value = Base64.getDecoder.decode(objSummary.contentMd5.value).map("%02x".format(_)).mkString),
        objSummary.contentLength,
        List()
      ),
      FileAudit(correlationId.value)
    )

  def save(sdesSubmission: SdesSubmission)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      _ <- sdesSubmissionRepository.delete(sdesSubmission._id.value)
      _ <- sdesSubmissionRepository.upsert(sdesSubmission)
    } yield ()

  def deleteSdesSubmission(correlationId: CorrelationId): Future[DeleteResult] =
    sdesSubmissionRepository
      .delete(correlationId.value)

  def search(
    page: Int,
    pageSize: Int,
    processed: Option[Boolean],
    status: Option[NotificationStatus],
    showBeforeAt: Option[Boolean]
  ): Future[SdesReportsPageData] = {
    val queryByProcessed =
      processed.fold(exists("_id"))(p => Filters.and(equal("isProcessed", p)))

    val queryByStatus =
      status.fold(queryByProcessed)(s => Filters.and(equal("status", NotificationStatus.fromName(s)), queryByProcessed))

    val query = if (showBeforeAt.getOrElse(false)) {
      Filters.and(
        queryByStatus,
        Filters.and(equal("isProcessed", false), Filters.lt("createdAt", LocalDateTime.now().minusHours(10)))
      )
    } else {
      queryByStatus
    }

    val orderBy = equal("createdAt", -1)
    val skip = page * pageSize
    for {
      sdesSubmissions <- sdesSubmissionRepository.page(query, orderBy, skip, pageSize)
      sdesSubmissionData <- sdesSubmissions.traverse(sdesSubmission =>
                              for {
                                jobData <- consolidatorJobDataRepository.findByEnvelopeId(sdesSubmission.envelopeId)
                              } yield SdesReportData.createSdesReportData(sdesSubmission, jobData)
                            )
      count <- sdesSubmissionRepository.count(query)
    } yield SdesReportsPageData(sdesSubmissionData, count)
  }

  def zipFiles(envelopeId: String): Future[Either[ObjectStoreError, ObjectSummaryWithMd5]] =
    objectStoreConnector.zipFiles(envelopeId)
}
