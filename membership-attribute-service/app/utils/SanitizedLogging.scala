package utils

import com.typesafe.scalalogging.StrictLogging
import org.slf4j.{Marker, MarkerFactory}

trait SanitizedLogging extends StrictLogging {
  val SanitizedLogMessage: Marker = MarkerFactory.getMarker("SENTRY")

  def logDebug(logMessage: String): Unit = {
    logger.debug(logMessage)
  }

  def logInfo(logMessage: String): Unit = {
    logger.info(logMessage)
  }

  def logWarn(logMessage: String): Unit = {
    logger.warn(logMessage)
  }

  def logWarn(logMessage: String, throwable: Throwable): Unit = {
    logger.warn(logMessage, throwable)
  }

  def logError(logMessage: LogMessage): Unit = {
    logger.error(logMessage.withPersonalData)
    logger.error(SanitizedLogMessage, logMessage.withoutPersonalData)
  }

  def logError(logMessage: LogMessage, throwable: Throwable): Unit = {
    logger.error(logMessage.withPersonalData, throwable)
    logger.error(SanitizedLogMessage, s"${logMessage.withoutPersonalData} due to ${throwable.getCause}")
  }
}

object Sanitizer {
  implicit class Sanitizer(val sc: StringContext) extends AnyVal {
    def scrub(args: Any*): LogMessage = {
      LogMessage(sc.s(args: _*), sc.s(args.map(_ => "*****"): _*))
    }
  }
}

case class LogMessage(withPersonalData: String, withoutPersonalData: String) {
  override val toString = withoutPersonalData
}
