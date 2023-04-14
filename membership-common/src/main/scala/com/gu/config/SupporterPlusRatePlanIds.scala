package com.gu.config

import com.gu.memsub.Subscription.ProductRatePlanId

case class SupporterPlusRatePlanIds(yearly: ProductRatePlanId,
  monthly: ProductRatePlanId
) extends ProductFamilyRatePlanIds {
    override val productRatePlanIds = Set(yearly, monthly)
}

object SupporterPlusRatePlanIds {
    def fromConfig(config: com.typesafe.config.Config) =
        SupporterPlusRatePlanIds(
            ProductRatePlanId(config.getString("yearly")),
            ProductRatePlanId(config.getString("monthly"))
        )
}

