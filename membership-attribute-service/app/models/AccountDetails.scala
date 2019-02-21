package models
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{GoCardless, PayPalMethod, PaymentCard}
import com.gu.services.model.PaymentDetails
import json.localDateWrites
import play.api.libs.json.{Json, _}
import org.joda.time.LocalDate.now

case class AccountDetails(
  regNumber: Option[String],
  email: Option[String],
  subscription : Subscription[SubscriptionPlan.AnyPlan],
  paymentDetails: PaymentDetails,
  stripePublicKey: String,
  accountHasMissedRecentPayments: Boolean,
  safeToUpdatePaymentMethod: Boolean,
  isAutoRenew: Boolean,
  alertText: Option[String]
)

object AccountDetails {

  implicit class ResultLike(accountDetails: AccountDetails) {

    import accountDetails._

    def toJson: JsObject = {

      val endDate = paymentDetails.chargedThroughDate
        .getOrElse(paymentDetails.termEndDate)

      val paymentMethod = paymentDetails.paymentMethod match {
        case Some(payPal: PayPalMethod) => Json.obj(
          "paymentMethod" -> "PayPal",
          "payPalEmail" -> payPal.email
        )
        case Some(card: PaymentCard) => Json.obj(
          "paymentMethod" -> "Card",
          "card" -> {
            Json.obj(
              "last4" -> card.paymentCardDetails.map(_.lastFourDigits).getOrElse[String]("••••"),
              "type" -> card.cardType.getOrElse[String]("unknown"),
              "stripePublicKeyForUpdate" -> stripePublicKey,
              "email" -> email
            )
          }
        )
        case Some(dd: GoCardless) => Json.obj(
          "paymentMethod" -> "DirectDebit",
          "account" -> Json.obj( // DEPRECATED
            "accountName" -> dd.accountName
          ),
          "mandate" -> Json.obj(
            "accountName" -> dd.accountName,
            "accountNumber" -> dd.accountNumber,
            "sortCode" -> dd.sortCode
          )
        )
        case _ if accountHasMissedRecentPayments && safeToUpdatePaymentMethod => Json.obj(
          "paymentMethod" -> "ResetRequired",
          "stripePublicKeyForCardAddition" -> stripePublicKey
        )
        case _ => Json.obj()
      }

      def planIsCurrent(plan: SubscriptionPlan.AnyPlan) = (plan.start == now || plan.start.isBefore(now)) && (plan match {
        case paidPlan: SubscriptionPlan.Paid => paidPlan.end.isAfter(now)
        case _ => true
      })

      def jsonifyPlan(plan: SubscriptionPlan.AnyPlan) = Json.obj(
        "productRatePlanId" -> plan.productRatePlanId.get, // consider exposing hash of this to avoid exposing internal IDs
        "productName" -> plan.productName,
        "name" -> plan.name,
        "start" -> plan.start,
        // if the customer acceptance date is future dated (e.g. 6for6) then always display, otherwise only show if starting less than 30 days from today
        "shouldBeVisible" -> (subscription.acceptanceDate.isAfter(now) || plan.start.isBefore(now.plusDays(30)))
      ) ++ (plan match {
        case paidPlan: SubscriptionPlan.Paid => Json.obj(
          "end" -> paidPlan.end,
          "chargedThrough" -> paidPlan.chargedThrough,
          "amount" -> paidPlan.charges.price.prices.head.amount * 100,
          "currency" -> paidPlan.charges.price.prices.head.currency.glyph,
          "currencyISO" -> paidPlan.charges.price.prices.head.currency.iso,
          "interval" -> paidPlan.charges.billingPeriod.noun
        )
        case _ => Json.obj()
      })

      Json.obj(
        "tier" -> paymentDetails.plan.name,
        "isPaidTier" -> (paymentDetails.plan.price.amount > 0f)
      ) ++
        regNumber.fold(Json.obj())({ reg => Json.obj("regNumber" -> reg) }) ++
        Json.obj(
          "joinDate" -> paymentDetails.startDate,
          "optIn" -> !paymentDetails.pendingCancellation,
          "subscription" -> (paymentMethod ++ Json.obj(
            "safeToUpdatePaymentMethod" -> safeToUpdatePaymentMethod,
            "start" -> paymentDetails.customerAcceptanceDate,
            "end" -> endDate,
            "nextPaymentPrice" -> paymentDetails.nextPaymentPrice,
            "nextPaymentDate" -> paymentDetails.nextPaymentDate,
            "lastPaymentDate" -> paymentDetails.lastPaymentDate,
            "renewalDate" -> paymentDetails.termEndDate,
            "cancelledAt" -> (paymentDetails.pendingAmendment || paymentDetails.pendingCancellation),
            "subscriberId" -> paymentDetails.subscriberId, // TODO remove once nothing is using this key (same time as removing old deprecated endpoints)
            "subscriptionId" -> paymentDetails.subscriberId,
            "trialLength" -> paymentDetails.remainingTrialLength,
            "autoRenew" -> isAutoRenew,
            "plan" -> Json.obj( // TODO remove once nothing is using this key (same time as removing old deprecated endpoints)
              "name" -> paymentDetails.plan.name,
              "amount" -> paymentDetails.plan.price.amount * 100,
              "currency" -> paymentDetails.plan.price.currency.glyph,
              "currencyISO" -> paymentDetails.plan.price.currency.iso,
              "interval" -> paymentDetails.plan.interval.mkString
            ),
            "currentPlans" -> subscription.plans.list.filter(planIsCurrent).sortBy(_.start.toDate).map(jsonifyPlan),
            "futurePlans" -> subscription.plans.list.filter(_.start.isAfter(now)).sortBy(_.start.toDate).map(jsonifyPlan)
          )),
        ) ++ alertText.map(text => Json.obj("alertText" -> text)).getOrElse(Json.obj())

    }
  }
}
