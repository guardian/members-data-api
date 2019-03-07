package models

import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{GoCardless, PaymentCard, PaymentMethod}
import com.gu.zuora.rest.ZuoraRestService.AccountSummary
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json

case class ExistingPaymentOption(
  accountSummary: AccountSummary,
  paymentMethodOption: Option[PaymentMethod],
  subscriptions: List[Subscription[SubscriptionPlan.AnyPlan]]
)

object ExistingPaymentOption {

  implicit class ResultLike(existingPaymentOption: ExistingPaymentOption) extends LazyLogging {

    import existingPaymentOption._

    def toJson = Json.obj(
      "billingAccountId" -> accountSummary.id.get,
      "currencyISO" -> accountSummary.currency.map(_.iso),
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
