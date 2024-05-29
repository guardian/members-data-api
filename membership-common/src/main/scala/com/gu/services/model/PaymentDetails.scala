package com.gu.services.model
import com.gu.memsub.subsv2.{Catalog, Subscription}
import com.gu.memsub.{PaymentMethod, Price}
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
    remainingTrialLength: Int,
    pendingCancellation: Boolean,
    paymentMethod: Option[PaymentMethod],
    plan: PersonalPlan,
)

object PaymentDetails {
  case class Payment(price: Price, date: LocalDate)
  implicit def dateToDateTime(date: LocalDate): DateTime = date.toDateTimeAtStartOfDay()

  def apply(
      sub: Subscription,
      paymentMethod: Option[PaymentMethod],
      nextPayment: Option[Payment],
      lastPaymentDate: Option[LocalDate],
      catalog: Catalog,
  ): PaymentDetails = {

    val firstPaymentDate = sub.firstPaymentDate
    val timeUntilPaying = Days.daysBetween(new LocalDate(DateTime.now()), new LocalDate(firstPaymentDate)).getDays
    import scala.math.BigDecimal.RoundingMode._

    val plan = sub.plan(catalog)
    PaymentDetails(
      pendingCancellation = sub.isCancelled,
      startDate = sub.startDate,
      chargedThroughDate = plan.chargedThroughDate,
      customerAcceptanceDate = sub.acceptanceDate,
      nextPaymentPrice = nextPayment.map(p => (BigDecimal.decimal(p.price.amount) * 100).setScale(2, HALF_UP).intValue),
      lastPaymentDate = lastPaymentDate,
      nextPaymentDate = nextPayment.map(_.date),
      termEndDate = sub.termEndDate,
      paymentMethod = paymentMethod,
      plan = PersonalPlan(
        name = plan.productName,
        price = plan.chargesPrice.prices.head,
        interval = plan.billingPeriod.leftMap(e => throw new RuntimeException("no billing period: " + e)).toOption.get.noun,
      ),
      subscriberId = sub.name.get,
      remainingTrialLength = timeUntilPaying,
    )
  }

  case class PersonalPlan(name: String, price: Price, interval: String)

}
