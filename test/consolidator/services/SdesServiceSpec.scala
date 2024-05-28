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

import com.mongodb.client.result.DeleteResult
import consolidator.connectors.{ ObjectStoreConnector, SdesConnector }
import consolidator.proxies.{ SdesConfig, SdesNotifyRequest }
import consolidator.repositories.{ ConsolidatorJobDataRepository, SdesSubmission, SdesSubmissionRepository }
import org.mockito.ArgumentMatchersSugar
import org.mockito.quality.Strictness
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.objectstore.client.{ Md5Hash, ObjectSummaryWithMd5 }

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SdesServiceSpec
    extends AnyWordSpec with IdiomaticMockito with ArgumentMatchersSugar with Matchers with ScalaFutures {

  trait TestFixture {
    val mockSdesConnector = mock[SdesConnector](withSettings.strictness(Strictness.LENIENT))
    val mockSdesConfig = mock[SdesConfig](withSettings.strictness(Strictness.LENIENT))
    val mockSdesSubmissionRepository = mock[SdesSubmissionRepository](withSettings.strictness(Strictness.LENIENT))
    val mockConsolidatorJobDataRepository =
      mock[ConsolidatorJobDataRepository](withSettings.strictness(Strictness.LENIENT))

    val objectSummary = ObjectSummaryWithMd5(File("test.txt"), 10L, Md5Hash("md5"), Instant.now())

    val sdesSubmission = SdesSubmission.createSdesSubmission("envelope-id", "submission-ref", 10L)
    val mockObjectStoreConnector = mock[ObjectStoreConnector](withSettings.strictness(Strictness.LENIENT))

    val sdesService =
      new SdesService(
        mockSdesConnector,
        mockSdesConfig,
        mockSdesSubmissionRepository,
        mockConsolidatorJobDataRepository,
        mockObjectStoreConnector
      )
  }

  "notifySDES" should {
    "notify SDES and insert the submission data" in new TestFixture {

      mockSdesConnector.notifySDES(*[SdesNotifyRequest]) shouldReturn Future.successful(Right(()))
      mockSdesSubmissionRepository.delete(*) shouldReturn Future.successful(DeleteResult.acknowledged(1))
      mockSdesSubmissionRepository.upsert(*[SdesSubmission]) shouldReturn Future.successful(Right(()))
      mockSdesConfig.informationType shouldReturn "informationType"
      mockSdesConfig.fileLocationUrl shouldReturn "/url"
      mockSdesConfig.recipientOrSender shouldReturn "recipient-Or-Sender"
      mockSdesConfig.baseUrl shouldReturn "/test"
      mockSdesConfig.headers shouldReturn Seq(
        "x-client-id" -> "test"
      )

      val future = sdesService.notifySDES("envelope-id", "submission-ref", objectSummary)

      whenReady(future) { _ =>
        mockSdesConnector.notifySDES(any[SdesNotifyRequest]) wasCalled once
        mockSdesSubmissionRepository.upsert(any[SdesSubmission]) wasCalled once
      }
    }
  }

}
