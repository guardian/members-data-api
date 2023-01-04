package monitoring

import akka.actor.ActorSystem
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, PutMetricDataResult}
import com.amazonaws.services.cloudwatch.{AmazonCloudWatchAsync, AmazonCloudWatchAsyncClient}
import com.gu.aws.CredentialsProvider
import com.typesafe.scalalogging.StrictLogging
import configuration.Stage

import scala.concurrent.{ExecutionContext, Future}

trait CloudWatch extends StrictLogging {
  val stage: String
  val application: String
  val service: String
  val cloudwatch: AmazonCloudWatchAsync
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

  def put(name: String, count: Double, extraDimensions: Dimension*): java.util.concurrent.Future[PutMetricDataResult] = {
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

  def measureDuration[T](metricName: String)(block: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    logger.debug(s"$metricName started...")
    put(metricName + " count", 1)
    val startTime = System.currentTimeMillis()

    def recordEnd[A](name: String)(value: A): A = {
      val duration = System.currentTimeMillis() - startTime
      put(name + " duration ms", duration)
      logger.debug(s"${service} $name completed in $duration ms")

      value
    }

    block.transform(recordEnd(metricName), recordEnd(s"$metricName failed"))
  }
}

class CreateMetrics(stage: Stage) {
  val cloudwatch: AmazonCloudWatchAsync = AmazonCloudWatchAsyncClient.asyncBuilder
    .withCredentials(CredentialsProvider)
    .withRegion(EU_WEST_1)
    .build()

  def forService(service: Class[_]): Metrics = Metrics(service.getSimpleName, stage.value, cloudwatch)
  def expensiveForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): ExpensiveMetrics =
    new ExpensiveMetrics(service.getSimpleName, stage.value, cloudwatch)
}

object CloudWatchHealth {
  var hasPushedMetricSuccessfully = false
}
