package monitoring

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import com.gu.monitoring.SafeLogger
import io.sentry.Sentry
import configuration.Config

import scala.collection.JavaConversions._

object SentryLogging {
  def init() {
    Config.sentryDsn match {
      case None => play.api.Logger.warn("No Sentry logging configured (OK for dev)")
      case Some(sentryDSN) =>
        play.api.Logger.info(s"Initialising Sentry logging")
        val sentryClient = Sentry.init(sentryDSN)
        val buildInfo: Map[String, String] = app.BuildInfo.toMap.mapValues(_.toString)
        val tags = Map("stage" -> Config.stage) ++ buildInfo
        sentryClient.setTags(tags)
    }
  }
}

class PiiFilter extends Filter[ILoggingEvent] {
  override def decide(event: ILoggingEvent): FilterReply = if (event.getMarker.contains(SafeLogger.sanitizedLogMessage)) FilterReply.ACCEPT
  else FilterReply.DENY
}
