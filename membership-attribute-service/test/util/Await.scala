package util

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Future, Await => ConcurrentAwait}
import scala.language.postfixOps

object Await {
  def waitFor[T](future: Future[T], duration: FiniteDuration = 100 millis) = ConcurrentAwait.result(future, duration)
}
