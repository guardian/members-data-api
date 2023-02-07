package subscriptions

import configuration.DiscountRatePlanIds
import models.subscription.BillingPeriod._
import models.subscription._
import models.subscription.promo.PercentDiscount
import models.subscription.promo.PercentDiscount.getDiscountScaledToPeriod
import _root_.services.zuora.soap.models.Commands._
import scalaz.syntax.std.option._

class Discounter(discountPlans: DiscountRatePlanIds) {
  import discountPlans.{percentageDiscount => discountPlan}

  private def getRatePlanScaledToPeriod(discount: PercentDiscount, period: PeriodType, billingPeriod: BillingPeriod) = discount.durationMonths.fold {
    RatePlan(
      discountPlan.planId.get,
      ChargeOverride(
        productRatePlanChargeId = discountPlan.planChargeId.get,
        discountPercentage = discount.amount.some,
        endDateCondition = SubscriptionEnd.some,
        billingPeriod = period.some,
      ).some,
    )

  } { durationInMonths =>
    val (newDiscountPercent, numberOfNewPeriods) = getDiscountScaledToPeriod(discount, billingPeriod)
    val opts = ChargeOverride(
      productRatePlanChargeId = discountPlan.planChargeId.get,
      discountPercentage = newDiscountPercent.some,
      endDateCondition = FixedPeriod(numberOfNewPeriods.toShort, period).some,
      billingPeriod = period.some,
    )

    RatePlan(discountPlan.planId.get, opts.some)
  }

  def getDiscountRatePlan(bp: BillingPeriod, discount: PercentDiscount) = bp match {
    case Month => getRatePlanScaledToPeriod(discount, Months, bp)
    case Quarter => getRatePlanScaledToPeriod(discount, Quarters, bp)
    case Year => getRatePlanScaledToPeriod(discount, Years, bp)
    case OneYear => getRatePlanScaledToPeriod(discount, SingleYear, bp)
  }
}
