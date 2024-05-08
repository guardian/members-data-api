package utils

import com.gu.monitoring.SafeLogger.LogPrefix

object TestLogPrefix {

  implicit val testLogPrefix: LogPrefix = LogPrefix("TestLogPrefix")

}
