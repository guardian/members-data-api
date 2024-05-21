package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.Product
import com.gu.memsub.Product.Membership
import com.gu.memsub.Subscription.{Feature, ProductRatePlanId, RatePlanId}
import com.gu.memsub.subsv2.{ChargeList, SubscriptionPlan}
import org.joda.time.LocalDate

object TestPaidSubscriptionPlan {
  def apply(
      id: RatePlanId = RatePlanId(randomId("ratePlan")),
      productRatePlanId: ProductRatePlanId = ProductRatePlanId(randomId("productRatePlan")),
      name: String = randomId("paidSubscriptionPlanName"),
      description: String = randomId("paidSubscriptionPlanDescription"),
      productName: String = randomId("paidSubscriptionPlanProductName"),
      lastChangeType: Option[String] = None,
      productType: String = randomId("paidSubscriptionPlanProductType"),
      product: Product = Membership,
      features: List[Feature] = Nil,
      charges: ChargeList = TestSingleCharge(),
      chargedThrough: Option[LocalDate] = None, // this is None if the sub hasn't been billed yet (on a free trial)
      start: LocalDate = LocalDate.now().minusDays(13),
      end: LocalDate = LocalDate.now().minusDays(13).plusYears(1),
  ): SubscriptionPlan = SubscriptionPlan(
    id: RatePlanId,
    productRatePlanId,
    name,
    description,
    productName,
    lastChangeType,
    productType,
    product,
    features,
    charges,
    chargedThrough,
    start,
    end: LocalDate,
  )
}
