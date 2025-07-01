package loghandling

import com.gu.monitoring.SafeLogging

object StopWatch {
  def apply() = new StopWatch
}

class StopWatch extends SafeLogging {
  private val startTime = System.currentTimeMillis

  def elapsed: Long = System.currentTimeMillis - startTime

  override def toString() = s"${elapsed}ms"
}
