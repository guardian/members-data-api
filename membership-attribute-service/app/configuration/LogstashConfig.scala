package configuration

import com.typesafe.config.Config
import configuration.OptionalConfig.{optionalBoolean, optionalConfig, optionalString}

class LogstashConfig(private val config: Config) {
  private val param = optionalConfig("param.logstash", config)
  val stream = param.flatMap(optionalString("stream", _))
  val streamRegion = param.flatMap(optionalString("streamRegion", _))
  val enabled = optionalBoolean("logstash.enabled", config) == Some(true)
  val stage = config.getString("stage")
}
