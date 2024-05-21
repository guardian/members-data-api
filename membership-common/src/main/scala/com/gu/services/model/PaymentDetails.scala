package com.gu.services.model
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{BillingPeriod, PaymentMethod, Price}
import com.gu.services.model.PaymentDetails.PersonalPlan
import org.joda.time.{DateTime, Days, LocalDate}
import com.github.nscala_time.time.Imports._

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
  ): PaymentDetails = {

    val firstPaymentDate = sub.firstPaymentDate
    val timeUntilPaying = Days.daysBetween(new LocalDate(DateTime.now()), new LocalDate(firstPaymentDate)).getDays
    import scala.math.BigDecimal.RoundingMode._

    PaymentDetails(
      pendingCancellation = sub.isCancelled,
      startDate = sub.startDate,
      chargedThroughDate = sub.plan.chargedThrough,
      customerAcceptanceDate = sub.acceptanceDate,
      nextPaymentPrice = nextPayment.map(p => (BigDecimal.decimal(p.price.amount) * 100).setScale(2, HALF_UP).intValue),
      lastPaymentDate = lastPaymentDate,
      nextPaymentDate = nextPayment.map(_.date),
      termEndDate = sub.termEndDate,
      paymentMethod = paymentMethod,
      plan = PersonalPlan.paid(sub),
      subscriberId = sub.name.get,
      remainingTrialLength = timeUntilPaying,
    )
  }

  case class PersonalPlan(name: String, price: Price, interval: String)

  object PersonalPlan {
    def paid(subscription: Subscription): PersonalPlan = PersonalPlan(
      name = subscription.plan.productName,
      price = subscription.plan.charges.price.prices.head,
      interval = subscription.plan.charges.billingPeriod.noun,
    )

  }
}
