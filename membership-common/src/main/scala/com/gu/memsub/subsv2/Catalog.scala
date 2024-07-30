package com.gu.memsub.subsv2

import com.gu.config.SubsV2ProductIds.ProductMap
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub.subsv2.Catalog.ProductRatePlanMap

object Catalog {

  type ProductRatePlanMap = Map[ProductRatePlanId, ProductRatePlan]

  // dummy ids for stripe (non zuora) products
  val guardianPatronProductRatePlanId: ProductRatePlanId = ProductRatePlanId("guardian_patron")
  val guardianPatronProductRatePlanChargeId: ProductRatePlanChargeId = ProductRatePlanChargeId("guardian_patron")

}

case class Catalog(productRatePlans: ProductRatePlanMap, products: ProductMap)
