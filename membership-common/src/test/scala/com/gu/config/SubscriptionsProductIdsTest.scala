package com.gu.config
import com.gu.memsub.Subscription.ProductId

import scala.jdk.CollectionConverters._
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.specs2.mutable.Specification

class SubscriptionsProductIdsTest extends Specification {

  "Paper rate plan IDs" should {

    /*
     * this test may seem pointless but it would be super
     * super easy to mix these variables up when refactoring
     */
    "Load the right days into the right variables" in {

      val config = ConfigFactory
        .empty()
        .withValue(
          "paper",
          ConfigValueFactory.fromMap(
            Map(
              "voucher" -> "voucherId",
              "delivery" -> "deliveryId",
              "digipack" -> "digipackId",
              "supporterPlus" -> "supporterPlus",
            ).asJava,
          ),
        )

      val productIds = SubscriptionsProductIds(config.getConfig("paper"))
      productIds.delivery mustEqual ProductId("deliveryId")
      productIds.voucher mustEqual ProductId("voucherId")
      productIds.digipack mustEqual ProductId("digipackId")
      productIds.supporterPlus mustEqual ProductId("supporterPlus")
    }

    "Work with the configs in here" in {

      val dev = ConfigFactory.parseResources("touchpoint.CODE.conf")
      val prod = ConfigFactory.parseResources("touchpoint.PROD.conf")

      SubscriptionsProductIds(dev.getConfig("touchpoint.backend.environments.CODE.zuora.productIds.subscriptions"))
      SubscriptionsProductIds(prod.getConfig("touchpoint.backend.environments.PROD.zuora.productIds.subscriptions"))
      done
    }
  }
}
