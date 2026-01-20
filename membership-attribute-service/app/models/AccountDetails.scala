package models
import com.gu.i18n.Country
import com.gu.memsub.ProductRatePlanChargeProductType.{Digipack, PaperDay}
import com.gu.memsub._
import com.gu.memsub.subsv2._
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.services.model.PaymentDetails
import json.localDateWrites
import org.joda.time.LocalDate
import play.api.libs.json._

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import scala.annotation.tailrec

case class AccountDetails(
    contactId: String,
    regNumber: Option[String],
    email: Option[String],
    deliveryAddress: Option[DeliveryAddress],
    subscription: Subscription,
    paymentDetails: PaymentDetails,
    billingCountry: Option[Country],
    stripePublicKey: String,
    accountHasMissedRecentPayments: Boolean,
    safeToUpdatePaymentMethod: Boolean,
    isAutoRenew: Boolean,
    alertText: Option[String],
    accountId: String,
    cancellationEffectiveDate: Option[String],
)

object AccountDetails {

  implicit class ResultLike(accountDetails: AccountDetails) extends SafeLogging {

    import accountDetails._

    def toJson(catalog: Catalog, today: LocalDate)(implicit logPrefix: LogPrefix): JsObject = {

      val mainPlan = subscription.plan(catalog, today)
      val product = mainPlan.product(catalog)

      val paymentMethod = paymentDetails.paymentMethod match {
        case Some(payPal: PayPalMethod) =>
          Json.obj(
            "paymentMethod" -> "PayPal",
            "payPalEmail" -> payPal.email,
          )
        case Some(card: PaymentCard) =>
          Json.obj(
            "paymentMethod" -> "Card",
            "card" -> {
              Json.obj(
                "last4" -> card.paymentCardDetails.map(_.lastFourDigits).getOrElse[String]("••••"),
                "expiry" -> card.paymentCardDetails.map(cardDetails =>
                  Json.obj(
                    "month" -> cardDetails.expiryMonth,
                    "year" -> cardDetails.expiryYear,
                  ),
                ),
                "type" -> card.cardType.getOrElse[String]("unknown"),
                "stripePublicKeyForUpdate" -> stripePublicKey,
                "email" -> email,
              )
            },
          )
        case Some(dd: GoCardless) =>
          Json.obj(
            "paymentMethod" -> "DirectDebit",
            "account" -> Json.obj( // DEPRECATED
              "accountName" -> dd.accountName,
            ),
            "mandate" -> Json.obj(
              "accountName" -> dd.accountName,
              "accountNumber" -> dd.accountNumber,
              "sortCode" -> dd.sortCode,
            ),
          )
        case Some(sepa: Sepa) =>
          Json.obj(
            "paymentMethod" -> "Sepa",
            "sepaMandate" -> Json.obj(
              "accountName" -> sepa.accountName,
              "iban" -> sepa.accountNumber,
            ),
          )
        case _ if accountHasMissedRecentPayments && safeToUpdatePaymentMethod =>
          Json.obj(
            "paymentMethod" -> "ResetRequired",
            "stripePublicKeyForCardAddition" -> stripePublicKey,
          )
        case _ => Json.obj()
      }

      def externalisePlanName(plan: RatePlan): Option[String] = plan.product(catalog) match {
        case _: Product.Weekly => if (plan.name(catalog).contains("Six for Six")) Some("currently on '6 for 6'") else None
        case _: Product.Paper => Some(plan.name(catalog).replace("+", " plus Digital Subscription"))
        case _ => None
      }

      def maybePaperDaysOfWeek(plan: RatePlan) = {
        val dayIndexes = for {
          charge <- plan.ratePlanCharges.list.toList
            .filterNot(_.pricing.isFree) // note 'Echo Legacy' rate plan has all days of week but some are zero price, this filters those out
          catalogZuoraPlan <- catalog.productRatePlans.get(plan.productRatePlanId)
          dayName <- catalogZuoraPlan.productRatePlanCharges
            .get(charge.productRatePlanChargeId)
            .collect { case benefit: PaperDay => benefit.dayOfTheWeekIndex }
        } yield dayName

        val dayNames = dayIndexes.sorted.map(DayOfWeek.of(_).getDisplayName(TextStyle.FULL, Locale.ENGLISH))

        if (dayNames.nonEmpty) Json.obj("daysOfWeek" -> dayNames) else Json.obj()
      }

      def jsonifyPlan(plan: RatePlan) = Json.obj(
        "tier" -> getTier(catalog, plan), // TODO deprecated - keep until MMA stops using it
        "mmaProductKey" -> getMMAProductKey(catalog, plan),
        "name" -> externalisePlanName(plan),
        "start" -> plan.effectiveStartDate,
        "end" -> plan.effectiveEndDate,
        // if the customer acceptance date is future dated (e.g. 6for6) then always display, otherwise only show if starting less than 30 days from today
        "shouldBeVisible" -> (subscription.customerAcceptanceDate.isAfter(today) || plan.effectiveStartDate.isBefore(today.plusDays(30))),
        "chargedThrough" -> plan.chargedThroughDate,
        "price" -> (plan.chargesPrice.prices.head.amount * 100).toInt,
        "currency" -> plan.chargesPrice.prices.head.currency.glyph,
        "currencyISO" -> plan.chargesPrice.prices.head.currency.iso,
        "billingPeriod" -> (plan.billingPeriod
          .leftMap(e => logger.warn("unknown billing period: " + e))
          .map(_.noun)
          .getOrElse("unknown_billing_period"): String),
        "features" -> plan.features.map(_.featureCode).mkString(","),
      ) ++ maybePaperDaysOfWeek(plan)

      val subscriptionData = new FilterPlans(subscription, catalog, today)

      val selfServiceCancellation = SelfServiceCancellation(product, billingCountry)

      val start = subscriptionData.startDate.getOrElse(paymentDetails.customerAcceptanceDate)
      val end = subscriptionData.endDate.getOrElse(paymentDetails.termEndDate)
      Json.obj(
        "tier" -> getTier(catalog, mainPlan), // TODO deprecated - keep until MMA stops using it
        "mmaProductKey" -> getMMAProductKey(catalog, mainPlan),
        "isPaidTier" -> (paymentDetails.plan.price.amount > 0f),
        "selfServiceCancellation" -> Json.obj(
          "isAllowed" -> selfServiceCancellation.isAllowed,
          "shouldDisplayEmail" -> selfServiceCancellation.shouldDisplayEmail,
          "phoneRegionsToDisplay" -> selfServiceCancellation.phoneRegionsToDisplay,
        ),
      ) ++
        regNumber.fold(Json.obj())({ reg => Json.obj("regNumber" -> reg) }) ++
        billingCountry.fold(Json.obj())({ bc => Json.obj("billingCountry" -> bc.name) }) ++
        Json.obj(
          "joinDate" -> paymentDetails.startDate,
          "optIn" -> !paymentDetails.pendingCancellation,
          "subscription" -> (paymentMethod ++ Json.obj(
            "contactId" -> accountDetails.contactId,
            "deliveryAddress" -> accountDetails.deliveryAddress,
            "safeToUpdatePaymentMethod" -> safeToUpdatePaymentMethod,
            "start" -> start,
            "end" -> end,
            "nextPaymentPrice" -> paymentDetails.nextPaymentPrice,
            "nextPaymentDate" -> paymentDetails.nextPaymentDate,
            "nextChargeDate" -> paymentDetails.nextChargeDate,
            "nextChargeAmount" -> paymentDetails.nextChargeAmount,
            "remainingDiscountDays" -> paymentDetails.remainingDiscountDays,
            "potentialCancellationDate" -> paymentDetails.nextInvoiceDate,
            "lastPaymentDate" -> paymentDetails.lastPaymentDate,
            "chargedThroughDate" -> paymentDetails.chargedThroughDate,
            "renewalDate" -> paymentDetails.termEndDate,
            "anniversaryDate" -> anniversary(start, today),
            "cancelledAt" -> paymentDetails.pendingCancellation,
            "subscriptionId" -> paymentDetails.subscriberId,
            "trialLength" -> paymentDetails.remainingTrialLength,
            "autoRenew" -> isAutoRenew,
            "plan" -> Json.obj( // TODO remove once nothing is using this key (same time as removing old deprecated endpoints)
              "name" -> paymentDetails.plan.name,
              "price" -> (paymentDetails.plan.price.amount * 100).toInt,
              "currency" -> paymentDetails.plan.price.currency.glyph,
              "currencyISO" -> paymentDetails.plan.price.currency.iso,
              "billingPeriod" -> paymentDetails.plan.interval.mkString,
            ),
            "currentPlans" -> subscriptionData.currentPlans.map(jsonifyPlan),
            "futurePlans" -> subscriptionData.futurePlans.map(jsonifyPlan),
            "readerType" -> accountDetails.subscription.readerType.value,
            "accountId" -> accountDetails.accountId,
            "cancellationEffectiveDate" -> cancellationEffectiveDate,
          )),
        ) ++ alertText.map(text => Json.obj("alertText" -> text)).getOrElse(Json.obj())

    }
  }

  /** Note this is a different concept than termEndDate because termEndDate could be many years in the future. termEndDate models when Zuora will
    * renew the subscription whilst anniversary indicates when another year will have passed since user started their subscription.
    *
    * @param start
    *   beginning of subscription timeline, perhaps customerAcceptanceDate
    * @param today
    *   where we are on the timeline today
    * @return
    *   next anniversary date of the subscription
    */
  def anniversary(
      start: LocalDate,
      today: LocalDate,
  ): LocalDate = {
    @tailrec def loop(current: LocalDate): LocalDate = {
      val next = current.plusYears(1)
      if (today.isBefore(next)) next
      else loop(next)
    }
    loop(start)
  }

  def getTier(catalog: Catalog, plan: RatePlan): Json.JsValueWrapper =
    plan.product(catalog) match {
      case Product.Delivery if plan.name(catalog) == "Sunday" => "Newspaper Delivery - Observer"
      case Product.DigitalVoucher if plan.name(catalog) == "Sunday" => "Newspaper Digital Voucher - Observer"
      case Product.Voucher if plan.name(catalog) == "Sunday" => "Newspaper Voucher - Observer"
      case _ => plan.productName
    }

  def getMMAProductKey(catalog: Catalog, plan: RatePlan): String =
    plan.product(catalog) match {
      case _: Product.Newspaper if plan.name(catalog) == "Sunday" => plan.productName + " - Observer"
      case _: Product.Newspaper if plan.getChargeTypes(catalog).contains(Digipack) => plan.productName + " + Digital"
      // TODO if it's the new tier 3 (digital only) return a different key (later)
      case _ => plan.productName
    }

}

class FilterPlans(subscription: Subscription, catalog: Catalog, today: LocalDate)(implicit val logPrefix: LogPrefix) extends SafeLogging {

  private val sortedPlans = subscription.ratePlans
    .filter(_.product(catalog) match {
      case _: Product.ContentSubscription => true
      case Product.UnknownProduct => false
      case Product.Membership => true
      case Product.GuardianPatron => true
      case Product.Contribution => true
      case Product.Discounts => false
      case Product.AdLite => true
    })
    .sortBy(_.effectiveStartDate.toDate)
  val currentPlans: List[RatePlan] = sortedPlans.filter(plan => !plan.effectiveStartDate.isAfter(today) && plan.effectiveEndDate.isAfter(today))
  val futurePlans: List[RatePlan] = sortedPlans.filter(plan => plan.effectiveStartDate.isAfter(today))

  val startDate: Option[LocalDate] = sortedPlans.headOption.map(_.effectiveStartDate)
  val endDate: Option[LocalDate] = sortedPlans.lastOption.map(_.effectiveEndDate)

  if (currentPlans.length > 1) logger.warn(s"More than one 'current plan' on sub with id: ${subscription.id}")

}

object CancelledSubscription {
  import AccountDetails._
  def apply(subscription: Subscription, catalog: Catalog): JsObject = {
    GetCurrentPlans
      .bestCancelledPlan(subscription)
      .map { plan =>
        Json.obj(
          "tier" -> getTier(catalog, plan),
          "mmaProductKey" -> getMMAProductKey(catalog, plan),
          "subscription" -> Json.obj(
            "subscriptionId" -> subscription.subscriptionNumber.getNumber,
            "cancellationEffectiveDate" -> subscription.termEndDate,
            "start" -> subscription.customerAcceptanceDate,
            "end" -> Seq(subscription.termEndDate, subscription.customerAcceptanceDate).max,
            "readerType" -> subscription.readerType.value,
            "accountId" -> subscription.accountId.get,
          ),
        )
      }
      .getOrElse(Json.obj())
  }

}
