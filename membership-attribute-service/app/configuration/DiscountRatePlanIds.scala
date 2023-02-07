package configuration

import models.subscription.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.typesafe.config.Config

case class DiscountRatePlan(planId: ProductRatePlanId, planChargeId: ProductRatePlanChargeId)
case class DiscountRatePlanIds(percentageDiscount: DiscountRatePlan)

object DiscountRatePlanIds {
  def fromConfig(config: Config) =
    DiscountRatePlanIds(
      DiscountRatePlan(
        ProductRatePlanId(config.getString("discount.percentage.plan")),
        ProductRatePlanChargeId(config.getString("discount.percentage.charge")),
      ),
    )
}
