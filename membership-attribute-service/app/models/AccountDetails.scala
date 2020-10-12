package models
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import scala.annotation.tailrec
import com.gu.i18n.Country
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, PaidSubscriptionPlan, PaperCharges, Subscription, SubscriptionPlan}
import com.gu.memsub.{GoCardless, PayPalMethod, PaymentCard, Product}
import com.gu.services.model.PaymentDetails
import com.typesafe.scalalogging.LazyLogging
import json.localDateWrites
import org.joda.time.LocalDate
import play.api.libs.json.{Json, _}
import org.joda.time.LocalDate.now

case class AccountDetails(
  contactId: String,
  regNumber: Option[String],
  email: Option[String],
  deliveryAddress: Option[DeliveryAddress],
  subscription : Subscription[SubscriptionPlan.AnyPlan],
  paymentDetails: PaymentDetails,
  billingCountry: Option[Country],
  stripePublicKey: String,
  accountHasMissedRecentPayments: Boolean,
  safeToUpdatePaymentMethod: Boolean,
  isAutoRenew: Boolean,
  alertText: Option[String],
  accountId: String,
  cancellationEffectiveDate: Option[String]
)

object AccountDetails {

  def externalisePlanName(plan: SubscriptionPlan.AnyPlan): Option[String] = plan.product match {
    case _: Product.Weekly => if(plan.name.contains("Six for Six")) Some("currently on '6 for 6'") else None
    case _: Product.Paper => Some(plan.name.replace("+",  " plus Digital Subscription"))
    case _ => None
  }

  def jsonifyPlan(plan: SubscriptionPlan.AnyPlan, subscription: Subscription[SubscriptionPlan.AnyPlan]): JsObject = {
    Json.obj(
      "name" -> externalisePlanName(plan),
      "start" -> plan.start,
      "end" -> plan.end,
      // if the customer acceptance date is future dated (e.g. 6for6) then always display, otherwise only show if starting less than 30 days from today
      "shouldBeVisible" -> (subscription.acceptanceDate.isAfter(now) || plan.start.isBefore(now.plusDays(30)))
    ) ++ (plan match {
      case paidPlan: PaidSubscriptionPlan[_, _] => Json.obj(
        "chargedThrough" -> paidPlan.chargedThrough,
        "amount" -> paidPlan.charges.price.prices.head.amount * 100,
        "currency" -> paidPlan.charges.price.prices.head.currency.glyph,
        "currencyISO" -> paidPlan.charges.price.prices.head.currency.iso,
        "interval" -> paidPlan.charges.billingPeriod.noun,
      )
      case _ => Json.obj()
    }) ++ (plan.charges match {
      case paperCharges: PaperCharges => Json.obj("daysOfWeek" ->
        paperCharges.dayPrices
          .filterNot(_._2.isFree) // note 'Echo Legacy' rate plan has all days of week but some are zero price, this filters those out
          .keys.toList
          .map(_.dayOfTheWeekIndex)
          .sorted
          .map(DayOfWeek.of)
          .map(_.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
      )
      case _ => Json.obj()
    })
  }

  def mmaCategoryFrom(product: Product): String = product match {
    case _: Product.Paper => "subscriptions" // Paper includes GW 🤦‍
    case _: Product.ZDigipack => "subscriptions"
    case _: Product.Contribution => "contributions"
    case _: Product.Membership => "membership"
    case _ => product.name // fallback
  }

  implicit class ResultLike(accountDetails: AccountDetails) extends LazyLogging {

    import accountDetails._

    def toJson: JsObject = {

      val product = accountDetails.subscription.plan.product

      val mmaCategory = mmaCategoryFrom(product)

      val endDate = paymentDetails.chargedThroughDate.getOrElse(paymentDetails.termEndDate)

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
              "expiry" -> card.paymentCardDetails.map(cardDetails => Json.obj(
                  "month" -> cardDetails.expiryMonth,
                "year" -> cardDetails.expiryYear
              )),
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

      val sortedPlans = subscription.plans.list.sortBy(_.start.toDate)
      val currentPlans = sortedPlans.filter(plan => !plan.start.isAfter(now) && plan.end.isAfter(now))
      val futurePlans = sortedPlans.filter(plan => plan.start.isAfter(now))

      val startDate: LocalDate = sortedPlans.headOption.map(_.start).getOrElse(paymentDetails.customerAcceptanceDate)

      if(currentPlans.length > 1) logger.warn(s"More than one 'current plan' on sub with id: ${subscription.id}")

      val selfServiceCancellation = SelfServiceCancellation(product, billingCountry)

      Json.obj(
        "mmaCategory" -> mmaCategory,
        "tier" -> paymentDetails.plan.name,
        "isPaidTier" -> (paymentDetails.plan.price.amount > 0f),
        "selfServiceCancellation" -> Json.obj(
          "isAllowed" -> selfServiceCancellation.isAllowed,
          "shouldDisplayEmail" -> selfServiceCancellation.shouldDisplayEmail,
          "phoneRegionsToDisplay" -> selfServiceCancellation.phoneRegionsToDisplay
        )
      ) ++
        regNumber.fold(Json.obj())({ reg => Json.obj("regNumber" -> reg) }) ++
        Json.obj(
          "joinDate" -> paymentDetails.startDate,
          "optIn" -> !paymentDetails.pendingCancellation,
          "subscription" -> (paymentMethod ++ Json.obj(
            "contactId" -> accountDetails.contactId,
            "deliveryAddress" -> accountDetails.deliveryAddress,
            "safeToUpdatePaymentMethod" -> safeToUpdatePaymentMethod,
            "start" -> startDate,
            "end" -> endDate,
            "nextPaymentPrice" -> paymentDetails.nextPaymentPrice,
            "nextPaymentDate" -> paymentDetails.nextPaymentDate,
            "lastPaymentDate" -> paymentDetails.lastPaymentDate,
            "chargedThroughDate" -> paymentDetails.chargedThroughDate,
            "renewalDate" -> paymentDetails.termEndDate,
            "anniversaryDate" -> anniversary(startDate),
            "cancelledAt" -> paymentDetails.pendingCancellation,
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
            "currentPlans" -> currentPlans.map(plan => jsonifyPlan(plan, subscription)),
            "futurePlans" -> futurePlans.map(plan => jsonifyPlan(plan, subscription)),
            "readerType" -> accountDetails.subscription.readerType.value,
            "accountId" -> accountDetails.accountId,
            "cancellationEffectiveDate" -> cancellationEffectiveDate
          )),
        ) ++ alertText.map(text => Json.obj("alertText" -> text)).getOrElse(Json.obj())

    }
  }

  /**
   * Note this is a different concept than termEndDate because termEndDate
   * could be many years in the future. termEndDate models when Zuora will
   * renew the subscription whilst anniversary indicates when another year
   * will have passed since user started their subscription.
   *
   * @param start beginning of subscription timeline, perhaps customerAcceptanceDate
   * @param today where we are on the timeline today
   * @return next anniversary date of the subscription
   */
  def anniversary(
    start: LocalDate,
    today: LocalDate = LocalDate.now()
  ): LocalDate = {
    @tailrec def loop(current: LocalDate): LocalDate = {
      val next = current.plusYears(1)
      if (today.isBefore(next)) next
      else loop(next)
    }
    loop(start)
  }
}

object CancelledSubscription {
  import AccountDetails._
  def apply(subscription: Subscription[AnyPlan]): JsObject = {
    GetCurrentPlans.bestCancelledPlan(subscription).map { plan =>
      Json.obj(
        "mmaCategory" -> mmaCategoryFrom(plan.product),
        "tier" -> plan.productName,
        "subscription" -> (Json.obj(
          "subscriptionId" -> subscription.name.get,
          "cancellationEffectiveDate" -> subscription.termEndDate,
          "start" -> subscription.acceptanceDate,
          "end" -> subscription.termEndDate,
          "readerType" -> subscription.readerType.value,
          "accountId" -> subscription.accountId.get,
        )),
      )
    }.getOrElse(Json.obj())
  }
}
