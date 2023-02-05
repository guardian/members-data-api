package configuration.ids

import com.typesafe.config.{Config, ConfigFactory}
import models.subscription.ProductFamily
import models.subscription.Subscription.ProductRatePlanId

trait ProductFamilyRatePlanIds {
  def productRatePlanIds: Set[ProductRatePlanId]
}

object ProductFamilyRatePlanIds {
  def config(context: Option[Config] = None)(env: String, productFamily: ProductFamily): Config =
    context.getOrElse(ConfigFactory.load).getConfig(s"touchpoint.backend.environments.$env.zuora.ratePlanIds.${productFamily.id}")
}
