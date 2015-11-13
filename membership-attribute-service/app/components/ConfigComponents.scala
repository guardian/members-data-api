package components
import configuration.Config

trait ConfigComponents {
  lazy val config: Config.type = Config
}
