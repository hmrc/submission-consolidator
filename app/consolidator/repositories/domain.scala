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

package consolidator.repositories

import cats.Eq
import collector.common.ApplicationError
import consolidator.repositories.NotificationStatus.FileReady
import consolidator.services.UniqueIdGenerator
import julienrf.json.derived
import org.bson.types.ObjectId
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.{ MongoFormats, MongoJavatimeFormats }

import java.time.Instant
import java.util.UUID

case class ConsolidatorJobData(
  projectId: String,
  startTimestamp: Instant,
  endTimestamp: Instant,
  lastObjectId: Option[ObjectId],
  error: Option[String],
  envelopeId: Option[String],
  _id: ObjectId = ObjectId.get()
)
object ConsolidatorJobData {

  implicit val format: OFormat[ConsolidatorJobData] = {
    implicit val instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    implicit val oidFormat: Format[ObjectId] = MongoFormats.objectIdFormat
    derived.oformat()
  }
}

abstract class ConsolidatorJobDataError(message: String) extends ApplicationError(message)
case class GenericConsolidatorJobDataError(message: String) extends ConsolidatorJobDataError(message)

abstract class SdesSubmissionError(message: String) extends ApplicationError(message)
case class GenericSdesSubmissionError(message: String) extends SdesSubmissionError(message)

final case class CorrelationId(value: String) extends AnyVal

object CorrelationId {
  implicit val format: Format[CorrelationId] =
    Format(_.validate[String].map(CorrelationId(_)), id => JsString(id.value))
}

case class SdesSubmission(
  envelopeId: String,
  submissionRef: String,
  submittedAt: Instant = Instant.now,
  isProcessed: Boolean = false,
  status: NotificationStatus,
  contentLength: Long,
  failureReason: Option[String] = None,
  confirmedAt: Option[Instant] = None,
  createdAt: Instant = Instant.now,
  lastUpdated: Option[Instant] = None,
  _id: CorrelationId = CorrelationId(UniqueIdGenerator.uuidStringGenerator.generate)
)

object SdesSubmission {
  def createSdesSubmission(envelopeId: String, submissionRef: String, contentLength: Long) =
    SdesSubmission(
      envelopeId,
      submissionRef,
      contentLength = contentLength,
      status = FileReady
    )

  implicit val formatUUID: Format[UUID] =
    Format(_.validate[String].map(UUID.fromString), uuid => JsString(uuid.toString))

  implicit val format: OFormat[SdesSubmission] = {
    implicit val instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    derived.oformat()
  }
}

sealed trait NotificationStatus extends Product with Serializable

object NotificationStatus {
  case object FileReady
      extends NotificationStatus //Indicates that the file specified in the notification is available to download from SDES

  case object FileReceived extends NotificationStatus //Indicates that the specified has been stored in SDES

  case object FileProcessingFailure extends NotificationStatus //The file specified has failed processing

  case object FileProcessed
      extends NotificationStatus //The file has passed all integrity checks and have been delivered to the recipient system in HMRC

  case object FileProcessedManualConfirmed
      extends NotificationStatus //The file has confirmed manually by an administrator

  implicit val catsEq: Eq[NotificationStatus] = Eq.fromUniversalEquals

  implicit val format: Format[NotificationStatus] = new Format[NotificationStatus] {
    override def writes(o: NotificationStatus): JsValue = o match {
      case FileReady                    => JsString("FileReady")
      case FileReceived                 => JsString("FileReceived")
      case FileProcessingFailure        => JsString("FileProcessingFailure")
      case FileProcessed                => JsString("FileProcessed")
      case FileProcessedManualConfirmed => JsString("FileProcessedManualConfirmed")
    }

    override def reads(json: JsValue): JsResult[NotificationStatus] =
      json match {
        case JsString("FileReady")                    => JsSuccess(FileReady)
        case JsString("FileReceived")                 => JsSuccess(FileReceived)
        case JsString("FileProcessingFailure")        => JsSuccess(FileProcessingFailure)
        case JsString("FileProcessed")                => JsSuccess(FileProcessed)
        case JsString("FileProcessedManualConfirmed") => JsSuccess(FileProcessedManualConfirmed)
        case JsString(err) =>
          JsError(s"only for valid FileReady, FileReceived, FileProcessingFailure or FileProcessed.$err is not allowed")
        case _ => JsError("Failure")
      }
  }

  def fromName(notificationStatus: NotificationStatus): String = notificationStatus match {
    case FileReady                    => "FileReady"
    case FileReceived                 => "FileReceived"
    case FileProcessingFailure        => "FileProcessingFailure"
    case FileProcessed                => "FileProcessed"
    case FileProcessedManualConfirmed => "FileProcessedManualConfirmed"
  }

}

case class SdesReportsPageData(sdesSubmissions: List[SdesReportData], count: Long)

object SdesReportsPageData {
  implicit val format: OFormat[SdesReportsPageData] = derived.oformat()
}

case class SdesReportData(
  consolidatorJobId: Option[String],
  startTimestamp: Option[Instant],
  endTimestamp: Option[Instant],
  correlationId: CorrelationId,
  envelopeId: String,
  submissionRef: String,
  submittedAt: Instant,
  status: NotificationStatus,
  failureReason: String,
  lastUpdated: Option[Instant]
)
object SdesReportData {

  def createSdesReportData(sdesSubmission: SdesSubmission, jobData: Option[ConsolidatorJobData]) =
    SdesReportData(
      jobData.map(_._id.toString),
      jobData.map(_.startTimestamp),
      jobData.map(_.endTimestamp),
      sdesSubmission._id,
      sdesSubmission.envelopeId,
      sdesSubmission.submissionRef,
      sdesSubmission.submittedAt,
      sdesSubmission.status,
      sdesSubmission.failureReason.getOrElse(""),
      sdesSubmission.lastUpdated
    )

  def fromSdesSubmission(sdesSubmission: SdesSubmission) =
    SdesReportData(
      None,
      None,
      None,
      sdesSubmission._id,
      sdesSubmission.envelopeId,
      sdesSubmission.submissionRef,
      sdesSubmission.submittedAt,
      sdesSubmission.status,
      sdesSubmission.failureReason.getOrElse(""),
      sdesSubmission.lastUpdated
    )
  implicit val oidFormat: Format[ObjectId] = MongoFormats.objectIdFormat
  implicit val format: OFormat[SdesReportData] = derived.oformat()
}
