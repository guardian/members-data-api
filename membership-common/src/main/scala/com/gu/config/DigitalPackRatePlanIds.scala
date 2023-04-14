package com.gu.config

import com.gu.memsub.Subscription.ProductRatePlanId

case class DigitalPackRatePlanIds(digitalPackYearly: ProductRatePlanId,
                                  digitalPackQuaterly: ProductRatePlanId,
                                  digitalPackMonthly: ProductRatePlanId
) extends ProductFamilyRatePlanIds {
    override val productRatePlanIds = Set(
        digitalPackYearly,
        digitalPackMonthly,
        digitalPackQuaterly
    )
}
object DigitalPackRatePlanIds {
    def fromConfig(config: com.typesafe.config.Config) =
        DigitalPackRatePlanIds(
            ProductRatePlanId(config.getString("yearly")),
            ProductRatePlanId(config.getString("quarterly")),
            ProductRatePlanId(config.getString("monthly"))
        )
}
