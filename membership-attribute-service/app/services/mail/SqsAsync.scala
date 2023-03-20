package services.mail

import com.typesafe.scalalogging.LazyLogging
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{GetQueueUrlRequest, SendMessageRequest}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.util.{Failure, Success}

/** Manages asynchronous access to SQS queues.
  */
class SqsAsync extends LazyLogging {

  val client = SqsAsyncClient.builder
    .region(EU_WEST_1)
    .credentialsProvider(AwsSQSSend.CredentialsProvider)
    .build()

  def send(queueName: QueueName, payload: String): Future[Unit] = {
    val futureQueueUrl =
      client
        .getQueueUrl(
          GetQueueUrlRequest.builder.queueName(queueName.value).build(),
        )
        .asScala
        .map(_.queueUrl)

    for {
      queueUrl <- futureQueueUrl
      _ <- Future.successful(logger.info(s"Sending message to SQS queue $queueUrl"))
      request = SendMessageRequest.builder.queueUrl(queueUrl).messageBody(payload).build()
      response = client.sendMessage(request).asScala
      _ <- response.transform {
        case Success(result) =>
          logger.info(s"Successfully sent message to $queueUrl: $result")
          Success(())
        case Failure(throwable) =>
          logger.error(s"Failed to send message to $queueUrl due to:", throwable)
          Failure(throwable)
      }
    } yield ()
  }
}
