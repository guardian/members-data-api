package configuration

import com.typesafe.config.Config
import configuration.OptionalConfig._

class LogstashConfig(private val config: Config) {
  private val param = config.optionalConfig("param.logstash")
  val stream = param.flatMap(_.optionalString("stream"))
  val streamRegion = param.flatMap(_.optionalString("streamRegion"))
  val enabled = config.optionalBoolean("logstash.enabled", false) == true
  val stage = config.getString("stage")
}
