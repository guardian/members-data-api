package services.mail

import scala.concurrent.Future

trait SendEmail {
  def apply(emailData: EmailData): Future[Unit]
}

class SendEmailToSQS(queueName: QueueName) extends SendEmail {
  val sendAsync = new SqsAsync

  override def apply(emailData: EmailData): Future[Unit] = sendAsync.send(queueName, emailData.toJson.toString)
}
