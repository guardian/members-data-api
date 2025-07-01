package com.gu.memsub.subsv2

import com.gu.config.SubsV2ProductIds
import com.typesafe.config.ConfigFactory

object Fixtures {
  lazy val config = ConfigFactory.parseResources("touchpoint.CODE.conf")
  lazy val productIds = SubsV2ProductIds.load(config.getConfig("touchpoint.backend.environments.CODE.zuora.productIds"))
}
