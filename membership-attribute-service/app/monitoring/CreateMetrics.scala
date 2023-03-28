package monitoring

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.cloudwatch.model.StandardUnit
import com.amazonaws.services.cloudwatch.{AmazonCloudWatchAsync, AmazonCloudWatchAsyncClient}
import com.gu.aws.CredentialsProvider
import configuration.{ApplicationName, Stage}

import scala.concurrent.ExecutionContext

trait CreateMetrics {
  def forService(service: Class[_]): Metrics
  def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics
}

class CreateRealMetrics(stage: Stage) extends CreateMetrics {
  private val cloudwatch: AmazonCloudWatchAsync = AmazonCloudWatchAsyncClient.asyncBuilder
    .withCredentials(CredentialsProvider)
    .withRegion(EU_WEST_1)
    .build()

  private def cloudWatchWrapper(service: Class[_]) =
    new CloudWatch(stage.value, ApplicationName.applicationName, service.getSimpleName, cloudwatch)

  def forService(service: Class[_]): Metrics = Metrics(service.getSimpleName, cloudWatchWrapper(service))

  def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics =
    new BatchedMetrics(cloudWatchWrapper(service))
}

object CreateNoopMetrics extends CreateMetrics {
  private object NoopCloudWatch extends CloudWatch("", "", "", null) {
    override protected[monitoring] def put(name: String, count: Double, unit: StandardUnit): Unit = ()
  }

  override def forService(service: Class[_]): Metrics = new Metrics("", NoopCloudWatch)

  override def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics = new BatchedMetrics(
    NoopCloudWatch,
  )
}
