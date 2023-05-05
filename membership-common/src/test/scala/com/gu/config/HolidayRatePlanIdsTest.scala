package com.gu.config
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

class HolidayRatePlanIdsTest extends Specification {

  "holiday rate plan IDs" should {

    "Work with the configs in here" in {

      val dev = ConfigFactory.parseResources("touchpoint.CODE.conf")
      val prod = ConfigFactory.parseResources("touchpoint.PROD.conf")

      HolidayRatePlanIds(dev.getConfig("touchpoint.backend.environments.CODE.zuora.ratePlanIds.discount.deliverycredit"))
      HolidayRatePlanIds(prod.getConfig("touchpoint.backend.environments.PROD.zuora.ratePlanIds.discount.deliverycredit"))
      done
    }
  }
}