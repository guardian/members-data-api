package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.Subscription.{ProductRatePlanId, RatePlanId}
import com.gu.memsub.subsv2.{RatePlan, RatePlanCharge}
import com.gu.zuora.rest.Feature
import org.joda.time.LocalDate
import scalaz.NonEmptyList

object TestPaidSubscriptionPlan {
  def apply(
      id: RatePlanId = RatePlanId(randomId("ratePlan")),
      productRatePlanId: ProductRatePlanId = ProductRatePlanId(randomId("productRatePlan")),
      productName: String = randomId("paidSubscriptionPlanProductName"),
      lastChangeType: Option[String] = None,
      features: List[Feature] = Nil,
      charges: NonEmptyList[RatePlanCharge] = NonEmptyList(TestSingleCharge()),
  ): RatePlan = RatePlan(
    id: RatePlanId,
    productRatePlanId,
    productName,
    lastChangeType,
    features,
    charges,
  )
}
