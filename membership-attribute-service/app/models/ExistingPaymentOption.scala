package models

import _root_.services.zuora.rest.ZuoraRestService.ObjectAccount
import com.gu.memsub._
import com.gu.memsub.subsv2.{Subscription, RatePlan}
import com.gu.monitoring.SafeLogging
import org.joda.time.LocalDate.now
import play.api.libs.json.Json

case class ExistingPaymentOption(
    freshlySignedIn: Boolean,
    objectAccount: ObjectAccount,
    paymentMethodOption: Option[PaymentMethod],
    subscriptions: List[Subscription],
)

object ExistingPaymentOption {

  implicit class ResultLike(existingPaymentOption: ExistingPaymentOption) extends SafeLogging {

    import existingPaymentOption._

    private def getSubscriptionFriendlyName(plan: RatePlan): String = plan.product match {
      case _: Product.Weekly => "Guardian Weekly"
      case _: Product.Membership => plan.productName + " Membership"
      case _: Product.Contribution => plan.name
      case _ => plan.productName // Newspaper & Digipack
    }

    private val sensitivePaymentPart = paymentMethodOption match {
      case Some(card: PaymentCard) =>
        Json.obj(
          "card" -> card.paymentCardDetails.map(_.lastFourDigits),
        )
      case Some(dd: GoCardless) =>
        Json.obj(
          "mandate" -> dd.accountNumber,
        )
      case _ => Json.obj()
    }

    private val sensitiveDetailIfApplicable = if (freshlySignedIn) {
      Json.obj(
        "billingAccountId" -> objectAccount.id.get,
        "subscriptions" -> subscriptions.map(subscription =>
          Json.obj(
            "billingAccountId" -> subscription.accountId.get, // this could be different to the top level one due to consolidation
            "isCancelled" -> subscription.isCancelled,
            "isActive" -> (!subscription.isCancelled && !subscription.termEndDate.isBefore(now)),
            "name" -> subscription.plans.list.headOption.map(getSubscriptionFriendlyName),
          ),
        ),
      ) ++ sensitivePaymentPart
    } else {
      Json.obj()
    }

    def toJson = Json.obj(
      "paymentType" -> (paymentMethodOption match {
        case Some(_: PaymentCard) => "Card"
        case Some(_: GoCardless) => "DirectDebit"
        case Some(_: PayPalMethod) => "PayPal"
        case _ => null
      }),
    ) ++ sensitiveDetailIfApplicable
  }
}
