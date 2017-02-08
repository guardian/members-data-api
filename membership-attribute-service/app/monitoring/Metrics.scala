package monitoring

import com.gu.monitoring.CloudWatch
import configuration.Config

case class Metrics(service: String) extends CloudWatch {
  val stage = Config.stage
  val application = Config.applicationName // This sets the namespace for Custom Metrics in AWS (see CloudWatch)
}
