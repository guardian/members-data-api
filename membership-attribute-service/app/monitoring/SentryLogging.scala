package monitoring

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import com.gu.monitoring.{SafeLogger, SafeLogging}
import configuration.SentryConfig
import io.sentry.Sentry

import scala.util.{Failure, Success, Try}

object SentryLogging extends SafeLogging {
  def init(config: SentryConfig): Unit = {
    config.sentryDsn match {
      case None => logger.warn("No Sentry logging configured (OK for dev)")
      case Some(sentryDSN) =>
        logger.info(s"Initialising Sentry logging")
        Try {
          Sentry.init(sentryDSN)
          val buildInfo: Map[String, String] = app.BuildInfo.toMap.view.mapValues(_.toString).toMap
          val tags = Map("stage" -> config.stage) ++ buildInfo
          tags.foreach { case (key, value) =>
            Sentry.setTag(key, value)
          }
        } match {
          case Success(_) => logger.debug("Sentry logging configured.")
          case Failure(e) => logger.error(scrub"Something went wrong when setting up Sentry logging ${e.getStackTrace}")
        }
    }

  }
}

class PiiFilter extends Filter[ILoggingEvent] {
  override def decide(event: ILoggingEvent): FilterReply = if (event.getMarker.contains(SafeLogger.sanitizedLogMessage)) FilterReply.ACCEPT
  else FilterReply.DENY
}
