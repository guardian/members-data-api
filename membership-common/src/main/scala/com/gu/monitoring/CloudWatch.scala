package com.gu.monitoring

import com.gu.aws.CredentialsProvider
import com.gu.monitoring.SafeLogger.LogPrefix
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, PutMetricDataResponse, StandardUnit}

import java.util.concurrent.Future

trait CloudWatch extends SafeLogging {
  val stage: String
  val application: String
  val service: String
  lazy val stageDimension = Dimension.builder().name("Stage").value(stage).build()
  lazy val servicesDimension = Dimension.builder().name("Services").value(service).build()
  def mandatoryDimensions: Seq[Dimension] = Seq(stageDimension, servicesDimension)
  protected def cloudWatchClient: CloudWatchAsyncClient = CloudWatch.cloudwatch

  def put(name: String, count: Double, extraDimensions: Dimension*)(implicit logPrefix: LogPrefix): Future[PutMetricDataResponse] = {
    val metric =
      MetricDatum
        .builder()
        .value(count)
        .metricName(name)
        .unit(StandardUnit.COUNT)
        .dimensions((mandatoryDimensions ++ extraDimensions): _*)
        .build()

    val request = PutMetricDataRequest.builder().namespace(application).metricData(metric).build()

    val result = cloudWatchClient.putMetricData(request)
    result.whenComplete { (_, exception) =>
      if (exception != null) {
        logger.info(s"CloudWatch PutMetricDataRequest error: ${exception.getMessage}")
      } else {
        logger.debug("CloudWatch PutMetricDataRequest - success")
        CloudWatchHealth.hasPushedMetricSuccessfully = true
      }
    }
    result
  }

  def put(name: String, count: Double, responseMethod: String)(implicit logPrefix: LogPrefix) {
    put(name, count, Dimension.builder().name("ResponseMethod").value(responseMethod).build())
  }
}

object CloudWatch {

  lazy val cloudwatch = CloudWatchAsyncClient
    .builder()
    .credentialsProvider(CredentialsProvider)
    .region(Region.EU_WEST_1)
    .build()

}
