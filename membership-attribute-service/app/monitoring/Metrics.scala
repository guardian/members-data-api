package monitoring

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import configuration.ApplicationName

case class Metrics(service: String, stage: String, cloudwatch: AmazonCloudWatchAsync) extends CloudWatch {
  val application = ApplicationName.applicationName // This sets the namespace for Custom Metrics in AWS (see CloudWatch)
}
