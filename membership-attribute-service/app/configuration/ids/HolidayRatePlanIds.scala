package configuration.ids

import com.typesafe.config.Config
import models.subscription.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
case class HolidayRatePlanIds(prpId: ProductRatePlanId, prpcId: ProductRatePlanChargeId)

object HolidayRatePlanIds {
  def apply(config: Config): HolidayRatePlanIds =
    HolidayRatePlanIds(
      prpId = ProductRatePlanId(config.getString("plan")),
      prpcId = ProductRatePlanChargeId(config.getString("charge")),
    )
}
