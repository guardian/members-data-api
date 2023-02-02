package monitoring

class SalesforceMetrics(cloudWatch: CloudWatch) extends Metrics("Salesforce", cloudWatch)
    with RequestMetrics
    with AuthenticationMetrics {

  def recordRequest() {
    putRequest
  }

  def recordAuthenticationError() {
    putAuthenticationError
  }
}
