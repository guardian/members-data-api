package config

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import configuration.ids.SubscriptionsProductIds
import models.subscription.Subscription.ProductId
import org.specs2.mutable.Specification

import scala.jdk.CollectionConverters._

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

      val dev = ConfigFactory.parseResources("touchpoint.DEV.conf")
      val uat = ConfigFactory.parseResources("touchpoint.UAT.conf")
      val prod = ConfigFactory.parseResources("touchpoint.PROD.conf")

      SubscriptionsProductIds(dev.getConfig("touchpoint.backend.environments.DEV.zuora.productIds.subscriptions"))
      SubscriptionsProductIds(uat.getConfig("touchpoint.backend.environments.UAT.zuora.productIds.subscriptions"))
      SubscriptionsProductIds(prod.getConfig("touchpoint.backend.environments.PROD.zuora.productIds.subscriptions"))
      done
    }
  }
}
