package acceptance.data

import acceptance.data.Randoms.randomId
import models.subscription
import models.subscription.{Benefit, BillingPeriod, Product}
import models.subscription.Product.Membership
import models.subscription.Subscription.{Feature, ProductRatePlanId, RatePlanId}
import models.subscription.subsv2.{PaidChargeList, PaidSubscriptionPlan}
import org.joda.time.LocalDate

import java.time.Month

object TestPaidSubscriptionPlan {
  def apply[P <: Product, C <: PaidChargeList](
      id: RatePlanId = RatePlanId(randomId("ratePlan")),
      productRatePlanId: ProductRatePlanId = ProductRatePlanId(randomId("productRatePlan")),
      name: String = randomId("paidSubscriptionPlanName"),
      description: String = randomId("paidSubscriptionPlanDescription"),
      productName: String = randomId("paidSubscriptionPlanProductName"),
      product: P = Membership,
      features: List[Feature] = Nil,
      charges: C = TestPaidCharge(),
      chargedThrough: Option[LocalDate] = None, // this is None if the sub hasn't been billed yet (on a free trial)
      start: LocalDate = LocalDate.now().minusDays(13),
      end: LocalDate = LocalDate.now().minusDays(13).plusYears(1),
  ): PaidSubscriptionPlan[P, C] = PaidSubscriptionPlan(
    id: RatePlanId,
    productRatePlanId,
    name,
    description,
    productName,
    product,
    features,
    charges,
    chargedThrough,
    start,
    end: LocalDate,
  )
}
