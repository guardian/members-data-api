package monitoring

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync

class ZuoraMetrics(val stage: String, val cloudwatch: AmazonCloudWatchAsync, val service: String = "Zuora")
    extends CloudWatch
    with StatusMetrics
    with RequestMetrics
    with AuthenticationMetrics {

  def countRequest(): Unit = putRequest // just a nicer name
}
