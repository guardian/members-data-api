package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.BillingPeriod.Year
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.RatePlanCharge
import com.gu.memsub.{Benefit, BillingPeriod, PricingSummary}

object TestSingleCharge {
  def apply[B <: Benefit, BP <: BillingPeriod](
      benefit: B = Benefit.Contributor,
      billingPeriod: BP = Year,
      price: PricingSummary = TestPricingSummary(),
      chargeId: ProductRatePlanChargeId = randomProductRatePlanChargeId(),
      subRatePlanChargeId: SubscriptionRatePlanChargeId = SubscriptionRatePlanChargeId(randomId("subscriptionRatePlanChargeId")),
  ): RatePlanCharge[B, BP] = RatePlanCharge[B, BP](benefit, billingPeriod, price, chargeId, subRatePlanChargeId)

  def randomProductRatePlanChargeId(): ProductRatePlanChargeId = ProductRatePlanChargeId(randomId("productRatePlanChargeId"))
}
