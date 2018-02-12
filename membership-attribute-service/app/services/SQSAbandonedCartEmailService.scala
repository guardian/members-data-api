package services

import configuration.Config

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SQSAbandonedCartEmailService {
  private val sqsClient = Config.sqsClient
  private val emailQueueUrl = Future { sqsClient.getQueueUrl(Config.abandonedCartEmailQueue) }
  def sendMessage(msg: String) = {
    for {
      queueUrl <- emailQueueUrl
      result <- Future{ sqsClient.sendMessage(queueUrl.getQueueUrl, msg) }
    } yield result
  }
}
