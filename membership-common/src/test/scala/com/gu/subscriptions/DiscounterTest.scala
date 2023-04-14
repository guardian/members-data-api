package com.gu.subscriptions

import com.gu.config.{DiscountRatePlan, DiscountRatePlanIds}
import com.gu.memsub._
import com.gu.memsub.Subscription._
import com.gu.memsub.promo._
import com.gu.zuora.soap.models.Commands._
import org.specs2.mutable.Specification
import scalaz.syntax.std.option._

class DiscounterTest extends Specification {

  val discountPrpId = ProductRatePlanId("discount")
  val discountPrpChargeId = ProductRatePlanChargeId("discount")

  def discount(time: Int, unit: PeriodType, percent: Double = 100) =
    RatePlan(discountPrpId.get,
            ChargeOverride(
              productRatePlanChargeId = discountPrpChargeId.get,
              discountPercentage = percent.some,
              endDateCondition = Some(FixedPeriod(time.toShort, unit)),
              billingPeriod = unit.some).some)

  val discountRatePlans = DiscountRatePlanIds(DiscountRatePlan(discountPrpId, discountPrpChargeId))
  def discounter = new Discounter(discountRatePlans)

  "Discounter" should {

    import BillingPeriod._

    "Create a normal monthly discount for a monthly rate plan" in {
      discounter.getDiscountRatePlan(Month, PercentDiscount(1.some, 100)) mustEqual discount(1, Months, 100)
    }

    "Create a 10% discount for a 1 year one-off rate plan" in {
      discounter.getDiscountRatePlan(OneYear, PercentDiscount(12.some, 10)) mustEqual discount(1, SingleYear, 10)
    }

    "Stretch out a 100% discount for 1 month to give you 33% off the entire quarter" in {
      discounter.getDiscountRatePlan(Quarter, PercentDiscount(1.some, 100)) mustEqual discount(1, Quarters, 33.34)
    }

    "Stretch out a 100% discount for 4 months to give you 66% off for two quarters" in {
      discounter.getDiscountRatePlan(Quarter, PercentDiscount(4.some, 100)) mustEqual discount(2, Quarters, 66.67)
    }

    "Stretch out a 100% discount for 6 months to give you 50% off the entire year" in {
      discounter.getDiscountRatePlan(Year, PercentDiscount(6.some, 100)) mustEqual discount(1, Years, 50)
    }

    "Stretch out a 100% discount for 18 months to give you 75% off for two years" in {
      discounter.getDiscountRatePlan(Year, PercentDiscount(18.some, 100)) mustEqual discount(2, Years, 75)
    }

    "Create an infinite rateplan for an infinite discount" in {
      discounter.getDiscountRatePlan(Month, PercentDiscount(None, 100)) mustEqual
        RatePlan(discountPrpId.get, ChargeOverride(productRatePlanChargeId = discountPrpChargeId.get, discountPercentage = 100d.some, endDateCondition = SubscriptionEnd.some, billingPeriod =  Months.some).some)

      discounter.getDiscountRatePlan(Year, PercentDiscount(None, 100)) mustEqual
        RatePlan(discountPrpId.get, ChargeOverride(productRatePlanChargeId = discountPrpChargeId.get, discountPercentage = 100d.some, endDateCondition = SubscriptionEnd.some, billingPeriod = Years.some).some)

    }
  }
}
