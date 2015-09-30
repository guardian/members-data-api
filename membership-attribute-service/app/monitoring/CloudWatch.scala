package monitoring

import java.util.concurrent.Future

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.regions.{Region, ServiceAbbreviations}
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest}
import configuration.Config
import play.Logger

class CloudWatch(region: Region,
                 stage: String,
                 application: String,
                 service: String) {

  lazy val  mandatoryDimensions = Seq(
    new Dimension().withName("Stage").withValue(stage),
    new Dimension().withName("Services").withValue(service))

  lazy val cloudwatch = {
    val client = new AmazonCloudWatchAsyncClient(new DefaultAWSCredentialsProviderChain)
    client.setEndpoint(region.getServiceEndpoint(ServiceAbbreviations.CloudWatch))
    client
  }

  def put(name: String, count: Double, extraDimensions: Dimension*): Future[Void] = {
    val metric = new MetricDatum()
      .withValue(count)
      .withMetricName(name)
      .withUnit("Count")
      .withDimensions(mandatoryDimensions ++ extraDimensions: _*)

    val request = new PutMetricDataRequest()
      .withNamespace(application)
      .withMetricData(metric)

    cloudwatch.putMetricDataAsync(request, LoggingAsyncHandler)
  }

  def put(name: String, count: Double, responseMethod: String) {
    put(name, count, new Dimension().withName("ResponseMethod").withValue(responseMethod))
  }
}

object LoggingAsyncHandler extends AsyncHandler[PutMetricDataRequest, Void] {
  def onError(exception: Exception) {
    Logger.info(s"CloudWatch PutMetricDataRequest error: ${exception.getMessage}}")
  }

  def onSuccess(request: PutMetricDataRequest, result: Void) {
    Logger.trace("CloudWatch PutMetricDataRequest - success")
  }
}

object CloudWatch {
  def apply(service: String) = new CloudWatch(Region.getRegion(Config.AWS.region), Config.stage, Config.applicationName, service)
}