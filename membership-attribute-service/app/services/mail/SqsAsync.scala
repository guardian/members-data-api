package services.mail

import com.typesafe.scalalogging.LazyLogging
import services.mail.SqsAsync.CredentialsProvider
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProviderChain, InstanceProfileCredentialsProvider, ProfileCredentialsProvider}
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{GetQueueUrlRequest, SendMessageRequest, SendMessageResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.util.{Failure, Success}

/** Manages asynchronous access to SQS queues.
  */
class SqsAsync extends LazyLogging {

  val client = SqsAsyncClient.builder
    .region(EU_WEST_1)
    .credentialsProvider(CredentialsProvider)
    .build()

  def send(queueName: QueueName, payload: String): Future[Unit] = {
    for {
      queueUrl <- queueUrlFor(queueName)
      _ <- sendToUrl(queueUrl, payload).transform {
        case Success(result) =>
          logger.info(s"Successfully sent message to $queueUrl: $result")
          Success(())
        case Failure(throwable) =>
          logger.error(s"Failed to sendToUrl message to $queueUrl due to:", throwable)
          Failure(throwable)
      }
    } yield ()
  }

  private def queueUrlFor(queueName: QueueName): Future[String] =
    client
      .getQueueUrl(
        GetQueueUrlRequest.builder.queueName(queueName.value).build(),
      )
      .asScala
      .map(_.queueUrl)

  private def sendToUrl(queueUrl: String, payload: String): Future[SendMessageResponse] = {
    val request = SendMessageRequest.builder.queueUrl(queueUrl).messageBody(payload).build()
    logger.info(s"Sending message to SQS queue $queueUrl:\n$payload")
    client.sendMessage(request).asScala
  }
}

object SqsAsync {
  val ProfileName = "membership"

  lazy val CredentialsProvider: AwsCredentialsProviderChain = AwsCredentialsProviderChain.builder
    .credentialsProviders(
      ProfileCredentialsProvider.create(ProfileName),
      InstanceProfileCredentialsProvider.create(),
    )
    .build()
}
