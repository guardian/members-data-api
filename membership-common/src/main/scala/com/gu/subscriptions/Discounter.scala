package com.gu.subscriptions
import com.gu.config.DiscountRatePlanIds
import com.gu.memsub.promo.PercentDiscount
import com.gu.zuora.soap.models.Commands._

import scalaz.syntax.std.option._
import com.gu.memsub._
import com.gu.memsub.promo.PercentDiscount.getDiscountScaledToPeriod
import BillingPeriod._

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
