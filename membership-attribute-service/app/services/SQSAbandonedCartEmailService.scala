package services

import configuration.Config
import scala.concurrent.ExecutionContext.Implicits.global

object SQSAbandonedCartEmailService {
  private val sqsClient = Config.sqsClient
  private val emailQueueUrl = sqsClient.getQueueUrl(Config.abandonedCartEmailQueue)
  def sendMessage(msg: String) = {
    val result = for {
      queueUrl <- emailQueueUrl
      res <- sqsClient.sendMessage(queueUrl, msg)
    } yield res
    result
  }
}
