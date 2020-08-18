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

package common

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{ Meter, MetricRegistry, Timer }
import com.kenshoo.play.metrics.Metrics
import org.mockito.ArgumentMatchersSugar
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.funspec.AnyFunSpec

import scala.concurrent.duration._

class MetricsClientTest extends AnyFunSpec with IdiomaticMockito with ArgumentMatchersSugar {

  trait TestFixture {
    val metrics = mock[Metrics]
    val mockMetricRegistry = mock[MetricRegistry]
    val mockTimer = mock[Timer]
    val mockMeter = mock[Meter]

    val metricsClient = new MetricsClient(metrics)

    val metricName = "some-metric"

    metrics.defaultRegistry shouldReturn mockMetricRegistry
  }

  describe("recordDuration") {
    it("should register a timer and update the value with the given duration") {
      new TestFixture {
        mockMetricRegistry.timer(*) shouldReturn mockTimer

        metricsClient.recordDuration(metricName, 1.seconds)

        metrics.defaultRegistry wasCalled once
        mockMetricRegistry.timer(metricName) wasCalled once
        mockTimer.update(1.seconds.toMillis, TimeUnit.MILLISECONDS) wasCalled once
      }
    }
  }

  describe("markMeter") {
    it("should register a metric and call mark()") {
      new TestFixture {
        mockMetricRegistry.meter(*) shouldReturn mockMeter
        mockMeter.mark() shouldDoNothing ()

        metricsClient.markMeter(metricName)

        metrics.defaultRegistry wasCalled once
        mockMetricRegistry.meter(metricName) wasCalled once
        mockMeter.mark() wasCalled once
      }
    }
  }

  describe("markMeter with eventCount") {
    it("should register a metric and call mark(eventCount)") {
      new TestFixture {
        mockMetricRegistry.meter(*) shouldReturn mockMeter
        mockMeter.mark(*) shouldDoNothing ()

        metricsClient.markMeter(metricName, 1)

        metrics.defaultRegistry wasCalled once
        mockMetricRegistry.meter(metricName) wasCalled once
        mockMeter.mark(1) wasCalled once
      }
    }
  }
}
