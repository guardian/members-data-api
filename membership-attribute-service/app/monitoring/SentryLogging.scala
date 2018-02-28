package monitoring

import io.sentry.Sentry
import configuration.Config
import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

object SentryLogging {
  def init() {
    println(Config.sentryDsn)
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
