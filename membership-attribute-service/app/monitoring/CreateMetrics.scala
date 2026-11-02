package monitoring

import org.apache.pekko.actor.ActorSystem
import com.gu.aws.CredentialsProvider
import configuration.{ApplicationName, Stage}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit

import scala.concurrent.ExecutionContext

trait CreateMetrics {
  def forService(service: Class[_]): Metrics
  def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics
}

class CreateRealMetrics(stage: Stage) extends CreateMetrics {
  private val cloudwatch: CloudWatchAsyncClient = CloudWatchAsyncClient
    .builder()
    .credentialsProvider(CredentialsProvider)
    .region(Region.EU_WEST_1)
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
