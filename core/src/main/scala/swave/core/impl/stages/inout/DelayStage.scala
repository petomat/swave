/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.concurrent.duration._
import scala.util.control.NonFatal
import swave.core.impl.{ Inport, Outport, StreamRunner }
import swave.core.{ Cancellable, PipeElem }
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class DelayStage(delayFor: AnyRef => FiniteDuration) extends InOutStage with PipeElem.InOut.Delay {

  def pipeElemType: String = "delay"
  def pipeElemParams: List[Any] = delayFor :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunnerAssignment(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport): State = {

    def awaitingDemand(): State =
      state(
        request = (n, _) => {
          in.request(1)
          awaitingNextElem(n.toLong)
        },

        cancel = stopCancelF(in),
        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        xEvent = { case StreamRunner.Timeout(_) => stay() })

    /**
     * @param remaining number of elements already requested by downstream but not yet delivered, > 0
     */
    def awaitingNextElem(remaining: Long): State = {
      requireState(remaining > 0)
      state(
        request = (n, _) => awaitingNextElem(remaining ⊹ n),
        cancel = stopCancelF(in),

        onNext = (elem, _) => {
          var funError: Throwable = null
          val d = try delayFor(elem) catch { case NonFatal(e) => { funError = e; Duration.Zero } }
          if (funError eq null) {
            if (d <= Duration.Zero) dispatch(elem, remaining)
            else awaitingTimeout(elem, runner.scheduleTimeout(this, d), remaining)
          } else {
            in.cancel()
            stopError(funError, out)
          }
        },

        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        xEvent = { case StreamRunner.Timeout(_) => stay() })
    }

    /**
     *
     * @param elem the currently delayed element
     * @param timer the currently active timer for `elem`
     * @param remaining number of elements already requested by downstream but not yet delivered, > 0
     */
    def awaitingTimeout(elem: AnyRef, timer: Cancellable, remaining: Long): State = {
      requireState(remaining > 0)
      state(
        request = (n, _) => awaitingTimeout(elem, timer, remaining ⊹ n),

        cancel = _ => {
          timer.cancel()
          stopCancel(in)
        },

        onComplete = _ => {
          timer.cancel()
          stopComplete(out)
        },

        onError = (e, _) => {
          timer.cancel()
          stopError(e, out)
        },

        xEvent = { case StreamRunner.Timeout(t) => if (t eq timer) dispatch(elem, remaining) else stay() })
    }

    def dispatch(elem: AnyRef, remaining: Long) = {
      out.onNext(elem)
      if (remaining > 1) {
        in.request(1)
        awaitingNextElem(remaining - 1)
      } else awaitingDemand()
    }

    awaitingDemand()
  }

}
