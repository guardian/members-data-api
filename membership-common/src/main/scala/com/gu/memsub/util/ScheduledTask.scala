package com.gu.memsub.util

import com.gu.monitoring.SafeLogging
import org.apache.pekko.actor.ActorSystem

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Use ScheduledTask only when initial value is well defined.
  */
trait ScheduledTask[T] extends SafeLogging {
  val initialValue: T

  val initialDelay: FiniteDuration
  val interval: FiniteDuration

  val name = getClass.getSimpleName

  implicit def system: ActorSystem
  implicit val executionContext: ExecutionContext

  private lazy val atomicReference = new AtomicReference[T](initialValue)

  def task(): Future[T]

  def start(): Unit = {
    logger.info(s"Starting $name scheduled task with an initial delay of: $initialDelay. This task will refresh every: $interval")
    system.scheduler.schedule(initialDelay, interval) {
      task.onComplete {
        case Success(t) => atomicReference.set(t)
        case Failure(e) => logger.errorNoPrefix(scrub"Scheduled task $name failed due to: $e. This task will retry in: $interval")
      }
    }
  }

  def get(): T = atomicReference.get()
}

object ScheduledTask {
  def apply[T](taskName: String, initValue: T, initDelay: FiniteDuration, intervalPeriod: FiniteDuration)(
      f: => Future[T],
  )(implicit actorSys: ActorSystem, ec: ExecutionContext) =
    new ScheduledTask[T] {
      val system = actorSys
      val executionContext = ec
      val initialValue = initValue
      val initialDelay = initDelay
      val interval = intervalPeriod
      override val name = taskName
      def task(): Future[T] = f
    }
}
