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

package consolidator.repositories

import cats.Eq
import collector.common.ApplicationError
import consolidator.repositories.NotificationStatus.FileReady
import consolidator.services.UniqueIdGenerator
import play.api.libs.json._
import reactivemongo.bson.BSONObjectID

import java.time.Instant
import java.util.UUID

case class ConsolidatorJobData(
  projectId: String,
  startTimestamp: Instant,
  endTimestamp: Instant,
  lastObjectId: Option[BSONObjectID],
  error: Option[String],
  envelopeId: Option[String],
  id: BSONObjectID = BSONObjectID.generate()
)
object ConsolidatorJobData {
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.{ mongoEntity, objectIdFormats }

  val instantWrites: Writes[Instant] = new Writes[Instant] {
    def writes(datetime: Instant): JsValue = Json.obj("$date" -> datetime.toEpochMilli)
  }

  val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  implicit val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  implicit val formats = mongoEntity {
    Json.format[ConsolidatorJobData]
  }
}

abstract class ConsolidatorJobDataError(message: String) extends ApplicationError(message)
case class GenericConsolidatorJobDataError(message: String) extends ConsolidatorJobDataError(message)

abstract class SdesSubmissionError(message: String) extends ApplicationError(message)
case class GenericSdesSubmissionError(message: String) extends SdesSubmissionError(message)

case class SdesSubmission(
  envelopeId: String,
  submissionRef: String,
  correlationId: String,
  submittedAt: Instant = Instant.now,
  isProcessed: Boolean = false,
  status: NotificationStatus,
  failureReason: Option[String] = None,
  confirmedAt: Option[Instant] = None,
  id: BSONObjectID = BSONObjectID.generate()
)

object SdesSubmission {
  def createSdesSubmission(envelopeId: String, submissionRef: String) =
    SdesSubmission(
      envelopeId,
      submissionRef,
      UniqueIdGenerator.uuidStringGenerator.generate,
      status = FileReady
    )

  implicit val formatUUID: Format[UUID] =
    Format(_.validate[String].map(UUID.fromString), uuid => JsString(uuid.toString))

  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.{ mongoEntity, objectIdFormats }

  val instantWrites: Writes[Instant] = new Writes[Instant] {
    def writes(datetime: Instant): JsValue = Json.obj("$date" -> datetime.toEpochMilli)
  }

  val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  implicit val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  implicit val formats = mongoEntity {
    Json.format[SdesSubmission]
  }
}

sealed trait NotificationStatus extends Product with Serializable

object NotificationStatus {

  case object FileReady extends NotificationStatus

  case object FileReceived extends NotificationStatus

  case object FileProcessingFailure extends NotificationStatus

  case object FileProcessed extends NotificationStatus

  implicit val catsEq: Eq[NotificationStatus] = Eq.fromUniversalEquals

  implicit val format: Format[NotificationStatus] = new Format[NotificationStatus] {
    override def writes(o: NotificationStatus): JsValue = o match {
      case FileReady             => JsString("FileReady")
      case FileReceived          => JsString("FileReceived")
      case FileProcessingFailure => JsString("FileProcessingFailure")
      case FileProcessed         => JsString("FileProcessed")
    }

    override def reads(json: JsValue): JsResult[NotificationStatus] =
      json match {
        case JsString("FileReady")             => JsSuccess(FileReady)
        case JsString("FileReceived")          => JsSuccess(FileReceived)
        case JsString("FileProcessingFailure") => JsSuccess(FileProcessingFailure)
        case JsString("FileProcessed")         => JsSuccess(FileProcessed)
        case JsString(err) =>
          JsError(s"only for valid FileReady, FileReceived, FileProcessingFailure or FileProcessed.$err is not allowed")
        case _ => JsError("Failure")
      }
  }

}
