package monitoring

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions.EU_WEST_1
import com.amazonaws.services.cloudwatch.{AmazonCloudWatchAsync, AmazonCloudWatchAsyncClient}
import configuration.Stage
import services.catalog.aws.CredentialsProvider

import scala.concurrent.ExecutionContext

class CreateMetrics(stage: Stage) {
  val cloudwatch: AmazonCloudWatchAsync = AmazonCloudWatchAsyncClient.asyncBuilder
    .withCredentials(CredentialsProvider)
    .withRegion(EU_WEST_1)
    .build()

  def forService(service: String): Metrics = Metrics(service, stage.value, cloudwatch)
  def forService(service: Class[_]): Metrics = forService(service.getSimpleName)
  def batchedForService(service: Class[_])(implicit system: ActorSystem, ec: ExecutionContext): BatchedMetrics =
    new BatchedMetrics(service.getSimpleName, stage.value, cloudwatch)
}
