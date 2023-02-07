package configuration

import models.subscription.Subscription.ProductId

case class SubscriptionsProductIds(voucher: ProductId, delivery: ProductId, digipack: ProductId, supporterPlus: ProductId)

object SubscriptionsProductIds {
  def apply(config: com.typesafe.config.Config): SubscriptionsProductIds = SubscriptionsProductIds(
    delivery = ProductId(config.getString("delivery")),
    voucher = ProductId(config.getString("voucher")),
    digipack = ProductId(config.getString("digipack")),
    supporterPlus = ProductId(config.getString("supporterPlus")),
  )
}
