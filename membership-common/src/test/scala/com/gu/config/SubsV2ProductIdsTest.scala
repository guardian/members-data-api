package com.gu.config
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

class SubsV2ProductIdsTest extends Specification {

  "Subs V2 product IDs" should {

    "Work with the configs in here" in {

      val dev = ConfigFactory.parseResources("touchpoint.CODE.conf")
      val prod = ConfigFactory.parseResources("touchpoint.PROD.conf")

      SubsV2ProductIds(dev.getConfig("touchpoint.backend.environments.CODE.zuora.productIds"))
      SubsV2ProductIds(prod.getConfig("touchpoint.backend.environments.PROD.zuora.productIds"))
      done
    }
  }
}