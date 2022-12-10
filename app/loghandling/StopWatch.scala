package loghandling

import com.typesafe.scalalogging.LazyLogging

object StopWatch {
  def apply() = new StopWatch
}

class StopWatch extends LazyLogging {
  private val startTime = System.currentTimeMillis

  def elapsed: Long = System.currentTimeMillis - startTime

  override def toString() = s"${elapsed}ms"
}
