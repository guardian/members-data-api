package com.gu.memsub.subsv2.reads
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads.{ProductIds, _}
import com.gu.memsub.subsv2.reads.CommonReads._
import scalaz.Validation.FlatMap._
import scalaz.ValidationNel
import scalaz.syntax.std.option._

object SubPlanReads {

  def anyPlanReads(
      ids: ProductIds,
      subZuoraPlan: SubscriptionZuoraPlan,
      catZuoraPlan: CatalogZuoraPlan,
  ): ValidationNel[String, SubscriptionPlan] =
    (for {
      charges <- readChargeList.read(catZuoraPlan.benefits, subZuoraPlan.charges.list.toList)
      product <- ids.productMap
        .get(catZuoraPlan.productId)
        .toSuccessNel(s"couldn't read a product as the product id is ${catZuoraPlan.productId} but we need one of $ids")
    } yield {
      val highLevelFeatures = subZuoraPlan.features.map(com.gu.memsub.Subscription.Feature.fromRest)
      SubscriptionPlan(
        subZuoraPlan.id,
        catZuoraPlan.id,
        catZuoraPlan.name,
        catZuoraPlan.description,
        subZuoraPlan.productName,
        subZuoraPlan.lastChangeType,
        catZuoraPlan.productType,
        product,
        highLevelFeatures,
        charges,
        subZuoraPlan.chargedThroughDate,
        subZuoraPlan.start,
        subZuoraPlan.end,
      )
    }).withTrace("anyPlanReads")

}
