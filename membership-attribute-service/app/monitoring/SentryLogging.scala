package monitoring

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import configuration.SentryConfig
import io.sentry.Sentry
import monitoring.SentryLogging.SanitizedLogMessage
import utils.SanitizedLogging
import utils.Sanitizer.Sanitizer

import scala.util.{Failure, Success, Try}

object SentryLogging extends SanitizedLogging {
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
          case Failure(e) => logError(scrub"Something went wrong when setting up Sentry logging ${e.getStackTrace}")
        }
    }

  }
}

class PiiFilter extends Filter[ILoggingEvent] {
  override def decide(event: ILoggingEvent): FilterReply = if (event.getMarker.contains(SanitizedLogMessage)) FilterReply.ACCEPT
  else FilterReply.DENY
}
