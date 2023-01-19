package configuration

import com.typesafe.config.Config
import configuration.OptionalConfig.optionalString

class SentryConfig(private val config: Config) {
  val stage = config.getString("stage")
  val sentryDsn = optionalString("sentry.dsn", config)
}
