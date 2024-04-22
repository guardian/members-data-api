package com.gu.monitoring

import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, PutMetricDataResult}
import com.gu.aws.CredentialsProvider
import com.gu.monitoring.CloudWatch.cloudwatch

import java.util.concurrent.Future

trait CloudWatch extends SafeLogging {
  val stage: String
  val application: String
  val service: String
  lazy val stageDimension = new Dimension().withName("Stage").withValue(stage)
  lazy val servicesDimension = new Dimension().withName("Services").withValue(service)
  def mandatoryDimensions: Seq[Dimension] = Seq(stageDimension, servicesDimension)

  trait LoggingAsyncHandler extends AsyncHandler[PutMetricDataRequest, PutMetricDataResult] {
    def onError(exception: Exception) {
      logger.info(s"CloudWatch PutMetricDataRequest error: ${exception.getMessage}}")
    }
    def onSuccess(request: PutMetricDataRequest, result: PutMetricDataResult) {
      logger.debug("CloudWatch PutMetricDataRequest - success")
      CloudWatchHealth.hasPushedMetricSuccessfully = true
    }
  }

  object LoggingAsyncHandler extends LoggingAsyncHandler

  def put(name: String, count: Double, extraDimensions: Dimension*): Future[PutMetricDataResult] = {
    val metric =
      new MetricDatum()
        .withValue(count)
        .withMetricName(name)
        .withUnit("Count")
        .withDimensions((mandatoryDimensions ++ extraDimensions): _*)

    val request = new PutMetricDataRequest().withNamespace(application).withMetricData(metric)

    cloudwatch.putMetricDataAsync(request, LoggingAsyncHandler)
  }

  def put(name: String, count: Double, responseMethod: String) {
    put(name, count, new Dimension().withName("ResponseMethod").withValue(responseMethod))
  }
}

object CloudWatch {

  lazy val cloudwatch = AmazonCloudWatchAsyncClient.asyncBuilder
    .withCredentials(CredentialsProvider)
    .withRegion(EU_WEST_1)
    .build()

}
