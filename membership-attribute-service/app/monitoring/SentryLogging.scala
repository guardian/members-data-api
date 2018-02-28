package monitoring

import io.sentry.Sentry
import configuration.Config
import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

object SentryLogging {

  val UserIdentityId = "userIdentityId"
  val UserGoogleId = "userGoogleId"
  val AllMDCTags = Set(UserIdentityId, UserGoogleId)

  def init() {
    play.api.Logger.info(s"Initialising Sentry logging")
    Try(Sentry.init(Config.sentryDsn)) match {
      case Failure(ex) => play.api.Logger.warn("Could not initialise sentry logging (OK for dev)")
      case Success(sentryClient) =>
        val buildInfo: Map[String, String] = app.BuildInfo.toMap.mapValues(_.toString)
        val tags = Map("stage" -> Config.stage) ++ buildInfo
        sentryClient.setTags(tags)
        sentryClient.setMdcTags(AllMDCTags)
    }
  }
}
