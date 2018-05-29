package monitoring

import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import io.sentry.Sentry
import configuration.Config

import scala.collection.JavaConversions._
import scala.util.{Failure, Try}

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
            sentryClient.setTags(tags)
          } match {
            case Failure(e) => SafeLogger.error(scrub"Something went wrong when setting up Sentry logging ${e.getStackTrace}")
          }
      }

  }
}
