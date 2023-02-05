package memsub.subsv2

import com.typesafe.config.ConfigFactory
import configuration.ids.SubsV2ProductIds

object Fixtures {
  lazy val uat = ConfigFactory.parseResources("touchpoint.UAT.conf")
  lazy val productIds = SubsV2ProductIds(uat.getConfig("touchpoint.backend.environments.UAT.zuora.productIds"))
}
