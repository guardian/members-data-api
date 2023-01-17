package monitoring

import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, PutMetricDataResult}
import com.typesafe.scalalogging.StrictLogging

trait CloudWatch extends StrictLogging {
  val stage: String
  val application: String
  val service: String
  val cloudwatch: AmazonCloudWatchAsync
  lazy val stageDimension = new Dimension().withName("Stage").withValue(stage)
  lazy val servicesDimension = new Dimension().withName("Services").withValue(service)
  def mandatoryDimensions: Seq[Dimension] = Seq(stageDimension, servicesDimension)

  val loggingAsyncHandler = new LoggingAsyncHandler()

  protected def put(name: String, count: Double, unit: String): Unit = {
    val metric =
      new MetricDatum()
        .withValue(count)
        .withMetricName(name)
        .withUnit(unit)
        .withDimensions(mandatoryDimensions: _*)

    val request = new PutMetricDataRequest().withNamespace(application).withMetricData(metric)

    cloudwatch.putMetricDataAsync(request, loggingAsyncHandler)
  }
}

class LoggingAsyncHandler extends AsyncHandler[PutMetricDataRequest, PutMetricDataResult] with StrictLogging {
  def onError(exception: Exception) {
    logger.info(s"CloudWatch PutMetricDataRequest error: ${exception.getMessage}}")
  }

  def onSuccess(request: PutMetricDataRequest, result: PutMetricDataResult) {
    logger.trace("CloudWatch PutMetricDataRequest - success")
  }
}
