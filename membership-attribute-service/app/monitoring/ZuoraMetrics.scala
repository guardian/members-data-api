package monitoring

class ZuoraMetrics(cloudWatch: CloudWatch)
    extends Metrics("Zuora", cloudWatch)
    with RequestMetrics
    with AuthenticationMetrics {

  def countRequest(): Unit = putRequest // just a nicer name
}
