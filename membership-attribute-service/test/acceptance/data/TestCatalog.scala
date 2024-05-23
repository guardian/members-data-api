package acceptance.data

import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap

object TestCatalog {
  def apply(map: Map[ProductRatePlanId, CatalogZuoraPlan] = Map.empty): CatalogMap = map
}
