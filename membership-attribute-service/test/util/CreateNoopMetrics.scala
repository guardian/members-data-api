package util

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import configuration.Stage
import monitoring.CreateMetrics
import org.specs2.mock.Mockito.mock

object CreateNoopMetrics extends CreateMetrics(Stage("none")) {
  override val cloudwatch = mock[AmazonCloudWatchAsync]
}
