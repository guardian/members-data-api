package models

import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{GoCardless, PaymentCard, PaymentMethod, Product}
import com.gu.zuora.rest.ZuoraRestService.ObjectAccount
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json.Json
import org.joda.time.LocalDate.now

case class ExistingPaymentOption(
  freshlySignedIn: Boolean,
  objectAccount: ObjectAccount,
  paymentMethodOption: Option[PaymentMethod],
  subscriptions: List[Subscription[SubscriptionPlan.AnyPlan]]
)

object ExistingPaymentOption {

  implicit class ResultLike(existingPaymentOption: ExistingPaymentOption) extends LazyLogging {

    import existingPaymentOption._

    private def getSubscriptionFriendlyName(plan: SubscriptionPlan.AnyPlan): String = plan.product match {
      case _: Product.Weekly => "Guardian Weekly"
      case _: Product.Membership => plan.productName + " Membership"
      case _: Product.Contribution => plan.name
      case _ => plan.productName // Newspaper & Digipack
    }

    private val paymentPart = paymentMethodOption match {
      case Some(card: PaymentCard) => Json.obj(
        "card" -> card.paymentCardDetails.map(_.lastFourDigits)
      )
      case Some(dd: GoCardless) => Json.obj(
        "mandate" -> dd.accountNumber
      )
      case _ => Json.obj()
    }

    private val sensitiveDetailIfApplicable = if (freshlySignedIn) {
      Json.obj(
        "billingAccountId" -> objectAccount.id.get,
        "subscriptions" -> subscriptions.map(subscription => Json.obj(
          "isCancelled" -> subscription.isCancelled,
          "isActive" -> (!subscription.isCancelled && subscription.termEndDate.isAfter(now)),
          "name" -> subscription.plans.list.headOption.map(getSubscriptionFriendlyName)
        ))) ++ paymentPart
    } else {
      Json.obj()
    }

    def toJson = Json.obj(
      "currencyISO" -> objectAccount.currency.map(_.iso),
    ) ++ sensitiveDetailIfApplicable
  }
}
