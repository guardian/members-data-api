package services.cancellationsBus

import com.gu.memsub.Subscription.SubscriptionNumber
import com.gu.monitoring.SafeLogger.LogPrefix
import play.api.libs.json._
import services.zuora.rest.OrderNumber

import scala.concurrent.Future

/** Published to the shared cancellations EventBridge bus (see support-service-lambdas' cancellations-bus handler).
  */
case class CancellationEvent private (subscriptionNumber: String, orderNumber: OrderNumber, allowUserNotifications: Boolean)

object CancellationEvent {
  val DetailType = "Cancellation"
  val Source = "ec2:members-data-api"

  private val ValidSubscriptionNumber = "^[A-Za-z0-9-]+$".r

  def forSelfServiceCancellation(subscriptionNumber: SubscriptionNumber, orderNumber: OrderNumber): Either[String, CancellationEvent] =
    Either.cond(
      ValidSubscriptionNumber.matches(subscriptionNumber.getNumber),
      new CancellationEvent(subscriptionNumber.getNumber, orderNumber, allowUserNotifications = true),
      s"invalid subscription number: '${subscriptionNumber.getNumber}'",
    )

  implicit val writes: OWrites[CancellationEvent] = OWrites { event =>
    Json.obj(
      "subscriptionNumber" -> event.subscriptionNumber,
      "orderNumber" -> event.orderNumber,
      "allowUserNotifications" -> event.allowUserNotifications,
    )
  }
}

trait PublishCancellationEvent {
  def publish(event: CancellationEvent)(implicit logPrefix: LogPrefix): Future[Unit]
}
