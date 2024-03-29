package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.Benefit.SupporterPlus
import com.gu.memsub.BillingPeriod.Year
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.PaidCharge
import com.gu.memsub.{Benefit, BillingPeriod, PricingSummary, Product}

object TestPaidCharge {
  def apply[B <: Benefit, BP <: BillingPeriod](
      benefit: B = Benefit.Friend,
      billingPeriod: BP = Year,
      price: PricingSummary = TestPricingSummary(),
      chargeId: ProductRatePlanChargeId = randomProductRatePlanChargeId(),
      subRatePlanChargeId: SubscriptionRatePlanChargeId = SubscriptionRatePlanChargeId(randomId("subscriptionRatePlanChargeId")),
  ): PaidCharge[B, BP] = PaidCharge[B, BP](benefit, billingPeriod, price, chargeId, subRatePlanChargeId)

  def randomProductRatePlanChargeId(): ProductRatePlanChargeId = ProductRatePlanChargeId(randomId("productRatePlanChargeId"))
}
