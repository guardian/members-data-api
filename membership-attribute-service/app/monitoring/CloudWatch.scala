package monitoring

import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, PutMetricDataResult, StandardUnit}
import com.typesafe.scalalogging.StrictLogging

class CloudWatch(stage: String, application: String, service: String, cloudwatch: AmazonCloudWatchAsync) extends StrictLogging {
  private lazy val stageDimension = new Dimension().withName("Stage").withValue(stage)
  private lazy val servicesDimension = new Dimension().withName("Services").withValue(service)

  private val mandatoryDimensions = Seq(stageDimension, servicesDimension)

  protected[monitoring] def put(name: String, count: Double, unit: StandardUnit, extraDimensions: Dimension*): Unit = {
    val metric =
      new MetricDatum()
        .withValue(count)
        .withMetricName(name)
        .withUnit(unit)
        .withDimensions(mandatoryDimensions ++ extraDimensions: _*)

    val request = new PutMetricDataRequest().withNamespace(application).withMetricData(metric)

    cloudwatch.putMetricDataAsync(request, LoggingAsyncHandler)
  }

  def putCount(name: String, count: Double): Unit = put(name, count, StandardUnit.Count)

  def putCount(name: String, count: Double, responseMethod: String): Unit =
    put(name, count, StandardUnit.Count, new Dimension().withName("ResponseMethod").withValue(responseMethod))
}

object LoggingAsyncHandler extends AsyncHandler[PutMetricDataRequest, PutMetricDataResult] with StrictLogging {
  def onError(exception: Exception) {
    logger.error(s"CloudWatch PutMetricDataRequest error: ${exception.getMessage}}")
  }

  def onSuccess(request: PutMetricDataRequest, result: PutMetricDataResult) {
    logger.trace("CloudWatch PutMetricDataRequest - success")
  }
}
