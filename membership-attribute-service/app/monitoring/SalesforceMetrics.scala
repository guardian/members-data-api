package monitoring

import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync

class SalesforceMetrics(val stage: String, val cloudwatch: AmazonCloudWatchAsync)
    extends CloudWatch
    with StatusMetrics
    with RequestMetrics
    with AuthenticationMetrics {

  val region = Region.getRegion(Regions.EU_WEST_1)
  val service = "Salesforce"

  def recordRequest() {
    putRequest
  }

  def recordResponse(status: Int, responseMethod: String) {
    putResponseCode(status, responseMethod)
  }

  def recordAuthenticationError() {
    putAuthenticationError
  }
}
