package acceptance.data

import acceptance.data.Randoms.randomId
import models.subscription.Benefit.SupporterPlus
import models.subscription.BillingPeriod.Year
import models.subscription.Subscription.{ProductRatePlanChargeId, SubscriptionRatePlanChargeId}
import models.subscription.subsv2.PaidCharge
import models.subscription.{Benefit, BillingPeriod, PricingSummary, Product}

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
