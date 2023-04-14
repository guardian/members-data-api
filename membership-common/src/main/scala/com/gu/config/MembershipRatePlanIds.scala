package com.gu.config

import com.gu.memsub.Subscription.ProductRatePlanId

case class MembershipRatePlanIds(friend: ProductRatePlanId,
                                 staff: ProductRatePlanId,
                                 supporterMonthly: ProductRatePlanId,
                                 supporterYearly: ProductRatePlanId,
                                 partnerMonthly: ProductRatePlanId,
                                 partnerYearly: ProductRatePlanId,
                                 patronMonthly: ProductRatePlanId,
                                 patronYearly: ProductRatePlanId,
                                 legacy: LegacyMembershipRatePlanIds) extends ProductFamilyRatePlanIds {

  val productRatePlanIds = Set(
    friend,
    staff,
    supporterMonthly,
    supporterYearly,
    partnerMonthly,
    partnerYearly,
    patronMonthly,
    patronYearly
  ) ++ legacy.productRatePlanIds
}

object MembershipRatePlanIds {
  def fromConfig(config: com.typesafe.config.Config) = {
    def prpId(s: String) = ProductRatePlanId(s)
    MembershipRatePlanIds(
      friend = prpId(config.getString("friend")),
      staff = prpId(config.getString("staff")),
      supporterMonthly = prpId(config.getString("supporter.monthly")),
      supporterYearly = prpId(config.getString("supporter.yearly")),
      partnerMonthly = prpId(config.getString("partner.monthly")),
      partnerYearly = prpId(config.getString("partner.yearly")),
      patronMonthly = prpId(config.getString("patron.monthly")),
      patronYearly = prpId(config.getString("patron.yearly")),
      legacy = LegacyMembershipRatePlanIds.fromConfig(config.getConfig("legacy"))
    )
  }
}