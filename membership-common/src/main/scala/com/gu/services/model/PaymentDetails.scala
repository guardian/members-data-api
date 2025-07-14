package com.gu.services.model
import com.gu.memsub.subsv2.{Catalog, Subscription}
import com.gu.memsub.{PaymentMethod, Price}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.services.model.PaymentDetails.PersonalPlan
import org.joda.time.{DateTime, Days, LocalDate}

import scala.language.implicitConversions

case class PaymentDetails(
    subscriberId: String,
    startDate: LocalDate,
    customerAcceptanceDate: LocalDate,
    chargedThroughDate: Option[LocalDate],
    termEndDate: LocalDate,
    nextPaymentPrice: Option[Int],
    lastPaymentDate: Option[LocalDate],
    nextPaymentDate: Option[LocalDate],
    nextInvoiceDate: Option[LocalDate],
    remainingTrialLength: Int,
    pendingCancellation: Boolean,
    paymentMethod: Option[PaymentMethod],
    plan: PersonalPlan,
)

object PaymentDetails extends SafeLogging {
  case class Payment(price: Price, date: LocalDate)
  implicit def dateToDateTime(date: LocalDate): DateTime = date.toDateTimeAtStartOfDay()

  def fromSubAndPaymentData(
      sub: Subscription,
      paymentMethod: Option[PaymentMethod],
      nextPayment: Option[Payment],
      nextInvoiceDate: Option[LocalDate],
      lastPaymentDate: Option[LocalDate],
      catalog: Catalog,
  )(implicit logPrefix: LogPrefix): PaymentDetails = {

    val firstPaymentDate = sub.firstPaymentDate
    val timeUntilPaying = Days.daysBetween(new LocalDate(DateTime.now()), new LocalDate(firstPaymentDate)).getDays
    import scala.math.BigDecimal.RoundingMode._

    val plan = sub.plan(catalog)
    PaymentDetails(
      pendingCancellation = sub.isCancelled,
      startDate = sub.contractEffectiveDate,
      chargedThroughDate = plan.chargedThroughDate,
      customerAcceptanceDate = sub.customerAcceptanceDate,
      // If nextPayment is None (no bills with amount > 0 found), return 0 instead of None.
      // This happens when users have free products or 100% discount/promo codes for more than 30 months
      nextPaymentPrice = nextPayment.map(p => (BigDecimal.decimal(p.price.amount) * 100).setScale(2, HALF_UP).intValue).orElse(Some(0)),
      lastPaymentDate = lastPaymentDate,
      nextPaymentDate = nextPayment.map(_.date),
      nextInvoiceDate = nextInvoiceDate,
      termEndDate = sub.termEndDate,
      paymentMethod = paymentMethod,
      plan = PersonalPlan(
        name = plan.productName,
        price = plan.chargesPrice.prices.head,
        interval = plan.billingPeriod.leftMap(e => logger.warn("unknown billing period: " + e)).map(_.noun).getOrElse("unknown_billing_period"),
      ),
      subscriberId = sub.subscriptionNumber.getNumber,
      remainingTrialLength = timeUntilPaying,
    )
  }

  case class PersonalPlan(name: String, price: Price, interval: String)

}
