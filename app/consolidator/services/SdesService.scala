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

import consolidator.connectors.SdesConnector
import consolidator.proxies._
import consolidator.repositories.{ SdesSubmission, SdesSubmissionRepository }
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5

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
  sdesSubmissionRepository: SdesSubmissionRepository
)(implicit ec: ExecutionContext) {

  def notifySDES(
    envelopeId: String,
    submissionRef: String,
    objWithSummary: ObjectSummaryWithMd5
  ): Future[Either[Exception, Unit]] = {
    val sdesSubmission = SdesSubmission.createSdesSubmission(envelopeId, submissionRef)
    val notifyRequest = createNotifyRequest(objWithSummary, sdesSubmission._id)
    for {
      res <- sdesConnector.notifySDES(notifyRequest)
      _   <- sdesSubmissionRepository.upsert(sdesSubmission)
    } yield res
  }

  private def createNotifyRequest(objSummary: ObjectSummaryWithMd5, correlationId: String): SdesNotifyRequest =
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
      FileAudit(correlationId)
    )

  def save(sdesSubmission: SdesSubmission)(implicit ec: ExecutionContext): Future[Unit] =
    for {
      _ <- sdesSubmissionRepository.delete(sdesSubmission._id)
      _ <- sdesSubmissionRepository.upsert(sdesSubmission)
    } yield ()

  def find(id: String): Future[Option[SdesSubmission]] =
    sdesSubmissionRepository.find(id)
}
