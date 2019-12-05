package monitoring

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import configuration.Config
import io.sentry.Sentry

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object SentryLogging {
  def init() {
      Config.sentryDsn match {
        case None => SafeLogger.warn("No Sentry logging configured (OK for dev)")
        case Some(sentryDSN) =>
          SafeLogger.info(s"Initialising Sentry logging")
          Try {
            val sentryClient = Sentry.init(sentryDSN)
            val buildInfo: Map[String, String] = app.BuildInfo.toMap.mapValues(_.toString)
            val tags = Map("stage" -> Config.stage) ++ buildInfo
            sentryClient.setTags(tags.asJava)
          } match {
            case Success(_) => SafeLogger.debug("Sentry logging configured.")
            case Failure(e) => SafeLogger.error(scrub"Something went wrong when setting up Sentry logging ${e.getStackTrace}")
          }
      }

  }
}

class PiiFilter extends Filter[ILoggingEvent] {
  override def decide(event: ILoggingEvent): FilterReply = if (event.getMarker.contains(SafeLogger.sanitizedLogMessage)) FilterReply.ACCEPT
  else FilterReply.DENY
}
