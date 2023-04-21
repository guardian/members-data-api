package services.mail

import play.api.libs.json.Json

import scala.concurrent.Future

trait SendEmail {
  def apply(emailData: EmailData): Future[Unit]
}

class SendEmailToSQS(queueName: QueueName) extends SendEmail {
  val sendAsync = new SqsAsync

  override def apply(emailData: EmailData): Future[Unit] = sendAsync.send(queueName, Json.prettyPrint(emailData.toJson))
}
