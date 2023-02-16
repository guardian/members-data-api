package configuration

import com.typesafe.config.Config

object OptionalConfig {
  implicit class RichConfig(config: Config) {
    def optionalValue[T](key: String, f: Config => T, config: Config): Option[T] =
      if (config.hasPath(key)) Some(f(config)) else None

    def optionalBoolean(key: String, default: Boolean): Boolean = optionalValue(key, _.getBoolean(key), config).getOrElse(default)

    def optionalString(key: String): Option[String] = optionalValue(key, _.getString(key), config)

    def optionalConfig(key: String): Option[Config] = optionalValue(key, _.getConfig(key), config)
  }
}
