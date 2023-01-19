package configuration

import com.typesafe.config.Config

object OptionalConfig {
  def optionalValue[T](key: String, f: Config => T, config: Config): Option[T] =
    if (config.hasPath(key)) Some(f(config)) else None

  def optionalString(key: String, config: Config): Option[String] = optionalValue(key, _.getString(key), config)
  def optionalBoolean(key: String, config: Config): Option[Boolean] = optionalValue(key, _.getBoolean(key), config)
  def optionalConfig(key: String, config: Config): Option[Config] = optionalValue(key, _.getConfig(key), config)
}
