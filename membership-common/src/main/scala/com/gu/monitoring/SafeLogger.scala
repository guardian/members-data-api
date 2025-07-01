package com.gu.monitoring

import com.gu.monitoring.SafeLogger.{LogMessage, LogPrefix}
import com.typesafe.scalalogging.Logger
import org.slf4j.{LoggerFactory, Marker, MarkerFactory}

trait SafeLogging {

  protected val logger: SafeLoggerImpl =
    new SafeLoggerImpl(Logger(LoggerFactory.getLogger(getClass.getName)))

  implicit class Sanitizer(val sc: StringContext) {
    def scrub(args: Any*): LogMessage = {
      LogMessage(sc.s(args: _*), sc.s(args.map(_ => "*****"): _*))
    }
  }

}

object SafeLogger extends SafeLogging {

  val sanitizedLogMessage: Marker = MarkerFactory.getMarker("SENTRY")

  case class LogMessage(withPersonalData: String, withoutPersonalData: String) {
    override val toString = withoutPersonalData
  }

  case class LogPrefix(message: String)

  object LogPrefix {
    val noLogPrefix: LogPrefix = LogPrefix("no-id")
  }

}

class SafeLoggerImpl(logger: Logger) {

  def debug(logMessage: String): Unit = {
    logger.debug(logMessage)
  }

  def infoNoPrefix(logMessage: String): Unit = {
    logger.info(logMessage)
  }

  def info(logMessage: String)(implicit logPrefix: LogPrefix): Unit = {
    logger.info(logPrefix.message + ": " + logMessage)
  }

  def warnNoPrefix(logMessage: String): Unit = {
    logger.warn(logMessage)
  }

  def warn(logMessage: String)(implicit logPrefix: LogPrefix): Unit = {
    logger.warn(logPrefix.message + ": " + logMessage)
  }

  def warnNoPrefix(logMessage: String, throwable: Throwable): Unit = {
    logger.warn(logMessage, throwable)
  }

  def warn(logMessage: String, throwable: Throwable)(implicit logPrefix: LogPrefix): Unit = {
    logger.warn(logPrefix.message + ": " + logMessage, throwable)
  }

  def errorNoPrefix(logMessage: LogMessage): Unit = {
    logger.error(logMessage.withPersonalData)
    logger.error(SafeLogger.sanitizedLogMessage, logMessage.withoutPersonalData)
  }

  def error(logMessage: LogMessage)(implicit logPrefix: LogPrefix): Unit = {
    logger.error(logPrefix.message + ": " + logMessage.withPersonalData)
    logger.error(SafeLogger.sanitizedLogMessage, logMessage.withoutPersonalData)
  }

  def errorNoPrefix(logMessage: LogMessage, throwable: Throwable): Unit = {
    logger.error(logMessage.withPersonalData, throwable)
    logger.error(SafeLogger.sanitizedLogMessage, s"${logMessage.withoutPersonalData} due to ${throwable.getCause}")
  }

  def error(logMessage: LogMessage, throwable: Throwable)(implicit logPrefix: LogPrefix): Unit = {
    logger.error(logPrefix.message + ": " + logMessage.withPersonalData, throwable)
    logger.error(SafeLogger.sanitizedLogMessage, s"${logMessage.withoutPersonalData} due to ${throwable.getCause}")
  }

}
