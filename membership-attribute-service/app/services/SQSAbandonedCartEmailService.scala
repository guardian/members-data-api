package services

import configuration.Config
import scala.concurrent.ExecutionContext.Implicits.global

object SQSAbandonedCartEmailService {
  private val sqsClient = Config.sqsClient
  private val emailQueueUrl = sqsClient.getQueueUrl(Config.abandonedCartEmailQueue)
  def sendMessage(msg: String) = {
    for {
      queueUrl <- emailQueueUrl
      result <- sqsClient.sendMessage(queueUrl, msg)
    } yield result
  }
}
