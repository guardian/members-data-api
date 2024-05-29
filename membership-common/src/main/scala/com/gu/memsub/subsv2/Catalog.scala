package com.gu.memsub.subsv2

import com.gu.config.SubsV2ProductIds.ProductIds
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub.subsv2.Catalog.CatalogMap

object Catalog {

  type CatalogMap = Map[ProductRatePlanId, ProductRatePlan]

  // dummy ids for stripe (non zuora) products
  val guardianPatronProductRatePlanId: ProductRatePlanId = ProductRatePlanId("guardian_patron")
  val guardianPatronProductRatePlanChargeId: ProductRatePlanChargeId = ProductRatePlanChargeId("guardian_patron")

}

case class Catalog(catalogMap: CatalogMap, productIds: ProductIds)
