package services.cancellationsBus

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import play.api.libs.json.Json
import services.aws.AwsCredentials
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.model.{PutEventsRequest, PutEventsRequestEntry, PutEventsResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

class PublishCancellationEventToEventBridge(busName: String, client: EventBridgeAsyncClient)(implicit ec: ExecutionContext)
    extends PublishCancellationEvent
    with SafeLogging {

  def this(busName: String)(implicit ec: ExecutionContext) =
    this(
      busName,
      EventBridgeAsyncClient.builder
        .region(EU_WEST_1)
        .credentialsProvider(AwsCredentials.CredentialsProvider)
        .build(),
    )

  override def publish(event: CancellationEvent)(implicit logPrefix: LogPrefix): Future[Unit] = {
    val detail = Json.stringify(Json.toJson(event))
    val request = PutEventsRequest.builder
      .entries(
        PutEventsRequestEntry.builder
          .eventBusName(busName)
          .source(CancellationEvent.Source)
          .detailType(CancellationEvent.DetailType)
          .detail(detail)
          .build(),
      )
      .build()

    logger.info(s"Publishing cancellation event to $busName: $detail")
    client
      .putEvents(request)
      .asScala
      .flatMap(assertNoFailedEntries)
      .transform(
        _ => {
          logger.info(s"Successfully published cancellation event to $busName")
          ()
        },
        throwable => {
          logger.error(scrub"Failed to publish cancellation event to $busName due to:", throwable)
          throwable
        },
      )
  }

  private def assertNoFailedEntries(response: PutEventsResponse): Future[Unit] =
    if (response.failedEntryCount() > 0) {
      val errors = response.entries().asScala.filter(_.errorCode() != null).map(e => s"${e.errorCode()}: ${e.errorMessage()}").mkString(", ")
      Future.failed(new RuntimeException(s"EventBridge rejected ${response.failedEntryCount()} entries: $errors"))
    } else Future.unit
}
