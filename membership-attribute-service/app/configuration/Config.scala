package configuration

import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.{Config, ConfigFactory}
import configuration.OptionalConfig.{optionalBoolean, optionalConfig, optionalString}
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
  private val param = optionalConfig("param.logstash", config)
  val stream = param.flatMap(optionalString("stream", _))
  val streamRegion = param.flatMap(optionalString("streamRegion", _))
  val enabled = optionalBoolean("logstash.enabled", config) == Some(true)
  val stage = config.getString("stage")
}

class SentryConfig(private val config: Config) {
  val stage = config.getString("stage")
  val sentryDsn = optionalString("sentry.dsn", config)
}

object OptionalConfig {
  def optionalValue[T](key: String, f: Config => T, config: Config): Option[T] =
    if (config.hasPath(key)) Some(f(config)) else None

  def optionalString(key: String, config: Config): Option[String] = optionalValue(key, _.getString(key), config)
  def optionalBoolean(key: String, config: Config): Option[Boolean] = optionalValue(key, _.getBoolean(key), config)
  def optionalConfig(key: String, config: Config): Option[Config] = optionalValue(key, _.getConfig(key), config)
}
