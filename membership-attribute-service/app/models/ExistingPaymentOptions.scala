package models

import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.{GoCardless, PaymentCard, PaymentMethod}
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json

case class ExistingPaymentOptions(
  groupedSubs : List[(AccountId, Option[PaymentMethod], List[Subscription[SubscriptionPlan.AnyPlan]])],
)

object ExistingPaymentOptions {

  implicit class ResultLike(existingPaymentOptions: ExistingPaymentOptions) extends LazyLogging {

    import existingPaymentOptions._

    def toJson = groupedSubs.map{
      case (accountId, paymentMethodOption, subscriptions) => Json.obj(
        "billingAccountId" -> accountId.get,
        "subscriptions" -> subscriptions.map(subscription => Json.obj(
          "subscriptionId" -> subscription.name.get
        ))
      ) ++ (paymentMethodOption match {
        case Some(card: PaymentCard) => Json.obj(
          "card" -> card.paymentCardDetails.map(_.lastFourDigits)
        )
        case Some(dd: GoCardless) => Json.obj(
          "mandate" -> dd.accountNumber
        )
        case _ => Json.obj()
      })
    }
  }
}
