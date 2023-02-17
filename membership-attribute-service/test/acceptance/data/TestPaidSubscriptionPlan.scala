package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub
import com.gu.memsub.{Benefit, BillingPeriod, Product}
import com.gu.memsub.Product.Membership
import com.gu.memsub.Subscription.{Feature, ProductRatePlanId, RatePlanId}
import com.gu.memsub.subsv2.{PaidChargeList, PaidSubscriptionPlan}
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
