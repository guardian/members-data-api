package configuration

import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.{Config, ConfigFactory}
import play.api.Configuration
import play.filters.cors.CORSConfig

import java.time.Duration
import scala.util.Try

object Config {

  lazy val config = ConfigFactory.load()
  val applicationName = "members-data-api"

  lazy val stage = config.getString("stage")

  lazy val testUsernames = TestUsernames(Encoder.withSecret(config.getString("identity.test.users.secret")), Duration.ofDays(2))

  lazy val corsConfig = CORSConfig.fromConfiguration(Configuration(config))

  lazy val mmaUpdateCorsConfig = corsConfig.copy(
    isHttpHeaderAllowed = Seq("accept", "content-type", "csrf-token", "origin").contains(_),
    isHttpMethodAllowed = Seq("POST", "OPTIONS").contains(_),
  )
}

class LogstashConfig(private val config: Config) {
  private val param = Try {config.getConfig("param.logstash") }.toOption
  val stream = Try { param.map(_.getString("stream")) }.toOption.flatten
  val streamRegion = Try { param.map(_.getString("streamRegion")) }.toOption.flatten
  val enabled = Try { config.getBoolean("logstash.enabled") }.toOption.contains(true)
  val stage = config.getString("stage")
}

class SentryConfig(private val config: Config) {
  val stage = config.getString("stage")
  val sentryDsn = if (config.hasPath("sentry.dsn")) Some(config.getString("sentry.dsn")) else None
}
