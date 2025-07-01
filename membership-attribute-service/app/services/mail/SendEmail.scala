package services.mail

import com.gu.monitoring.SafeLogger.LogPrefix
import play.api.libs.json.Json

import scala.concurrent.Future

trait SendEmail {
  def send(emailData: EmailData)(implicit logPrefix: LogPrefix): Future[Unit]
}

class SendEmailToSQS(queueName: QueueName) extends SendEmail {
  val sendAsync = new SqsAsync

  override def send(emailData: EmailData)(implicit logPrefix: LogPrefix): Future[Unit] = sendAsync.send(queueName, Json.prettyPrint(emailData.toJson))
}
