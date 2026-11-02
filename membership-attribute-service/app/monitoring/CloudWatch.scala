package monitoring

import com.gu.monitoring.SafeLogging
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, StandardUnit}

class CloudWatch(stage: String, application: String, service: String, cloudwatch: CloudWatchAsyncClient) extends SafeLogging {
  private lazy val stageDimension = Dimension.builder().name("Stage").value(stage).build()
  private lazy val servicesDimension = Dimension.builder().name("Services").value(service).build()

  private val mandatoryDimensions = Seq(stageDimension, servicesDimension)

  protected[monitoring] def put(name: String, count: Double, unit: StandardUnit): Unit = {
    val metric =
      MetricDatum
        .builder()
        .value(count)
        .metricName(name)
        .unit(unit)
        .dimensions(mandatoryDimensions: _*)
        .build()

    val request = PutMetricDataRequest.builder().namespace(application).metricData(metric).build()

    cloudwatch.putMetricData(request).whenComplete { (_, exception) =>
      if (exception != null) {
        logger.errorNoPrefix(scrub"CloudWatch PutMetricDataRequest error: ${exception.getMessage}")
      } else {
        logger.debug("CloudWatch PutMetricDataRequest - success")
      }
    }
  }
}
