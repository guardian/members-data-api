package configuration

import models.subscription.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.typesafe.config.Config
case class HolidayRatePlanIds(prpId: ProductRatePlanId, prpcId: ProductRatePlanChargeId)

object HolidayRatePlanIds {
  def apply(config: Config): HolidayRatePlanIds =
    HolidayRatePlanIds(
      prpId = ProductRatePlanId(config.getString("plan")),
      prpcId = ProductRatePlanChargeId(config.getString("charge")),
    )
}
