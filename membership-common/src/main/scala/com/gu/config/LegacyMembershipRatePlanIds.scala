package com.gu.config

import com.gu.memsub.Subscription.ProductRatePlanId

case class LegacyMembershipRatePlanIds(friend: ProductRatePlanId,
                                       supporterMonthly: ProductRatePlanId,
                                       supporterYearly: ProductRatePlanId,
                                       partnerMonthly: ProductRatePlanId,
                                       partnerYearly: ProductRatePlanId,
                                       patronMonthly: ProductRatePlanId,
                                       patronYearly: ProductRatePlanId) {

  val productRatePlanIds = Set(
    friend,
    supporterMonthly,
    supporterYearly,
    partnerMonthly,
    partnerYearly,
    patronMonthly,
    patronYearly
  )
}

object LegacyMembershipRatePlanIds {
  def fromConfig(config: com.typesafe.config.Config) = {
    def prpId(s: String) = ProductRatePlanId(s)

    LegacyMembershipRatePlanIds(
      friend = prpId(config.getString("friend")),
      supporterMonthly = prpId(config.getString("supporter.monthly")),
      supporterYearly = prpId(config.getString("supporter.yearly")),
      partnerMonthly = prpId(config.getString("partner.monthly")),
      partnerYearly = prpId(config.getString("partner.yearly")),
      patronMonthly = prpId(config.getString("patron.monthly")),
      patronYearly = prpId(config.getString("patron.yearly"))
    )
  }
}