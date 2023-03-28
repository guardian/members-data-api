package configuration

import com.typesafe.config.Config
import configuration.OptionalConfig._

class SentryConfig(private val config: Config) {
  val stage = config.getString("stage")
  val sentryDsn = config.optionalString("sentry.dsn")
}
