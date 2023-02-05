package config

import com.typesafe.config.ConfigFactory
import configuration.HolidayRatePlanIds
import org.specs2.mutable.Specification

class HolidayRatePlanIdsTest extends Specification {

  "holiday rate plan IDs" should {

    "Work with the configs in here" in {

      val dev = ConfigFactory.parseResources("touchpoint.DEV.conf")
      val uat = ConfigFactory.parseResources("touchpoint.UAT.conf")
      val prod = ConfigFactory.parseResources("touchpoint.PROD.conf")

      HolidayRatePlanIds(dev.getConfig("touchpoint.backend.environments.DEV.zuora.ratePlanIds.discount.deliverycredit"))
      HolidayRatePlanIds(uat.getConfig("touchpoint.backend.environments.UAT.zuora.ratePlanIds.discount.deliverycredit"))
      HolidayRatePlanIds(prod.getConfig("touchpoint.backend.environments.PROD.zuora.ratePlanIds.discount.deliverycredit"))
      done
    }
  }
}
