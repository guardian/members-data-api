package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.PricingSummary
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.{RatePlanCharge, SubscriptionEnd, ZBillingPeriod, ZYear}

object TestSingleCharge {
  def apply(
      billingPeriod: ZBillingPeriod = ZYear,
      price: PricingSummary = TestPricingSummary(),
      chargeId: ProductRatePlanChargeId = randomProductRatePlanChargeId(),
      subRatePlanChargeId: SubscriptionRatePlanChargeId = SubscriptionRatePlanChargeId(randomId("subscriptionRatePlanChargeId")),
  ): RatePlanCharge = RatePlanCharge(subRatePlanChargeId, chargeId, price, Some(billingPeriod), None, SubscriptionEnd, None, None)

  def randomProductRatePlanChargeId(): ProductRatePlanChargeId = ProductRatePlanChargeId(
    randomId("productRatePlanChargeId"),
  ) // was contributor by default
}
