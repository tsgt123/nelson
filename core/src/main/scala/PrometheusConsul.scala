//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson

import scalaz.{\/-, -\/, ~>}
import scalaz.concurrent.Task
import helm.ConsulOp
import helm.ConsulOp._

class PrometheusConsul private (instance: String, interp: ConsulOp ~> Task, metrics: Metrics)
    extends (ConsulOp ~> Task) {

  def apply[A](op: ConsulOp[A]): Task[A] =
    Task.delay(System.nanoTime).flatMap { startNanos =>
      interp(op).attempt.flatMap { att =>
        val elapsed = System.nanoTime - startNanos
        val label = toLabel(op)
        metrics.helmRequestsLatencySeconds.labels(label, instance).observe(elapsed / 1.0e9)
        att match {
          case \/-(a) =>
            Task.now(a)
          case -\/(e) =>
            metrics.helmRequestsFailuresTotal.labels(label, instance).inc()
            Task.fail(e)
        }
      }
    }

  private def toLabel(op: ConsulOp[_]) = op match {
    case _: Get => "get"
    case _: Set => "set"
    case _: Delete => "delete"
    case _: ListKeys => "listKeys"
    case _: HealthCheck => "healthCheck"
  }
}

object PrometheusConsul {

  /** Instruments a helm interpreter with Prometheus metrics.
   *  @param consulInstance an identifier for the instance of consul we're talking to
   *  @param interp a helm interpreter to wrap
   *  @param registry the CollectorRegistry to record to; defaults to CollectorRegistry.default
   */
  def apply(consulInstance: String, interp: ConsulOp ~> Task, metrics: Metrics = Metrics.default): ConsulOp ~> Task =
    new PrometheusConsul(consulInstance, interp, metrics)
}
