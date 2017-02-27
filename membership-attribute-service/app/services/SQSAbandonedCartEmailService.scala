package services

import com.amazonaws.regions.{Regions, Region}
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{SendMessageRequest, CreateQueueRequest}
import com.gu.aws._
import configuration.Config

object SQSAbandonedCartEmailService {
  private val sqsClient = new AmazonSQSClient(CredentialsProvider)
  sqsClient.setRegion(Region.getRegion(Regions.EU_WEST_1))
  private val emailQueueUrl = sqsClient.createQueue(new CreateQueueRequest(Config.abandonedCartEmailQueue)).getQueueUrl

  def sendMessage(msg: String) = {
    sqsClient.sendMessage(new SendMessageRequest(emailQueueUrl, msg))
  }
}
