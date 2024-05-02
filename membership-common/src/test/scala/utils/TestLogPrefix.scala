package utils

import com.gu.monitoring.SafeLogger.LogPrefix

object TestLogPrefix {

  implicit val testLogPrefix: LogPrefix = new LogPrefix {
    override def message: String = "TestLogPrefix"
  }

}
