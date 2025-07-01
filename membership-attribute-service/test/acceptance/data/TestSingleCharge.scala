package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.memsub.PricingSummary
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.{RatePlanCharge, SubscriptionEnd, ZBillingPeriod, ZYear}
import org.joda.time.LocalDate

object TestSingleCharge {
  def apply(
      billingPeriod: ZBillingPeriod = ZYear,
      price: PricingSummary = TestPricingSummary(),
      chargeId: ProductRatePlanChargeId = randomProductRatePlanChargeId(),
      subRatePlanChargeId: SubscriptionRatePlanChargeId = SubscriptionRatePlanChargeId(randomId("subscriptionRatePlanChargeId")),
      chargedThroughDate: Option[LocalDate] = None, // this is None if the sub hasn't been billed yet (on a free trial)
      effectiveStartDate: LocalDate = LocalDate.now().minusDays(13),
      effectiveEndDate: LocalDate = LocalDate.now().minusDays(13).plusYears(1),
  ): RatePlanCharge = RatePlanCharge(
    subRatePlanChargeId,
    chargeId,
    price,
    Some(billingPeriod),
    None,
    SubscriptionEnd,
    None,
    None,
    chargedThroughDate,
    effectiveStartDate,
    effectiveEndDate,
  )

  def randomProductRatePlanChargeId(): ProductRatePlanChargeId = ProductRatePlanChargeId(
    randomId("productRatePlanChargeId"),
  ) // was contributor by default
}
