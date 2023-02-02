package monitoring

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.cloudwatch.model.{Dimension, StandardUnit}
import com.amazonaws.services.cloudwatch.{AmazonCloudWatchAsync, AmazonCloudWatchAsyncClient}
import com.gu.aws.CredentialsProvider
import configuration.{ApplicationName, Stage}

import scala.concurrent.ExecutionContext

trait CreateMetrics {
  def forService(service: String): Metrics
  def forService(service: Class[_]): Metrics = forService(service.getSimpleName)
  def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics
}

class CreateRealMetrics(stage: Stage) extends CreateMetrics {
  private val cloudwatch: AmazonCloudWatchAsync = AmazonCloudWatchAsyncClient.asyncBuilder
    .withCredentials(CredentialsProvider)
    .withRegion(EU_WEST_1)
    .build()

  private def cloudWatchWrapper(service: String) =
    new CloudWatch(stage.value, ApplicationName.applicationName, service, cloudwatch)

  def forService(service: String): Metrics = Metrics(service, cloudWatchWrapper(service))
  def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics =
    new BatchedMetrics(cloudWatchWrapper(service.getSimpleName))
}

object CreateNoopMetrics extends CreateMetrics {
  private object NoopCloudWatch extends CloudWatch("", "", "", null) {
    override protected[monitoring] def put(name: String, count: Double, unit: StandardUnit, extraDimensions: Dimension*): Unit = ()
  }

  override def forService(service: String): Metrics = Metrics("", NoopCloudWatch)

  override def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics = new BatchedMetrics(
    NoopCloudWatch,
  )
}
