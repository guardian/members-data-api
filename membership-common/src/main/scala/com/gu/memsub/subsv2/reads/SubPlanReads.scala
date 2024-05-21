package com.gu.memsub.subsv2.reads
import com.gu.memsub.Product._
import com.gu.memsub.Product
import com.gu.memsub.Subscription.ProductId
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads.{ProductIds, _}
import com.gu.memsub.subsv2.reads.CommonReads._
import com.gu.memsub.{Benefit, BillingPeriod}

import scalaz.Validation.FlatMap._
import scalaz.std.list._
import scalaz.syntax.monad._
import scalaz.syntax.nel._
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import scalaz.{Validation, ValidationNel, \/}
import scalaz.syntax.apply.ToApplyOps

/** Convert a catalog zuora plan and a subscription zuora plan into some type A Between them, the catalog zuora plan & sub zuora plan have enough info
  * to construct a Plan
  */
trait SubPlanReads[A] {
  def read(p: ProductIds, z: SubscriptionZuoraPlan, c: CatalogZuoraPlan): ValidationNel[String, A]
}

object SubPlanReads {

  def findProduct[P <: Product](id: ProductIds => List[ProductId], product: P): SubPlanReads[P] = new SubPlanReads[P] {

    override def read(p: ProductIds, z: SubscriptionZuoraPlan, c: CatalogZuoraPlan): ValidationNel[String, P] = {
      id(p)
        .contains(c.productId)
        .option(product)
        .toSuccess(s"couldn't read a $product: as the product id is ${c.productId} but we need one of $p".wrapNel)
    }
  }

  private val voucherReads: SubPlanReads[Voucher] = findProduct(_.voucher.point[List], Voucher)
  private val digitalVoucherReads: SubPlanReads[DigitalVoucher] = findProduct(_.digitalVoucher.point[List], DigitalVoucher)
  private val deliveryReads: SubPlanReads[Delivery] = findProduct(_.delivery.point[List], Delivery)
  private val nationalDeliveryReads: SubPlanReads[NationalDelivery] = findProduct(_.nationalDelivery.point[List], NationalDelivery)
  private val zDigipackReads: SubPlanReads[ZDigipack] = findProduct(_.digipack.point[List], Digipack)
  private val supporterPlusReads: SubPlanReads[SupporterPlus] = findProduct(_.supporterPlus.point[List], SupporterPlus)
  private val membershipReads: SubPlanReads[Membership] = findProduct(ids => List(ids.supporter, ids.partner, ids.patron), Membership)
  private val contributionReads: SubPlanReads[Contribution] = findProduct(_.contributor.point[List], Contribution)
  private val weeklyZoneAReads: SubPlanReads[WeeklyZoneA] = findProduct(_.weeklyZoneA.point[List], WeeklyZoneA)
  private val weeklyZoneBReads: SubPlanReads[WeeklyZoneB] = findProduct(_.weeklyZoneB.point[List], WeeklyZoneB)
  private val weeklyZoneCReads: SubPlanReads[WeeklyZoneC] = findProduct(_.weeklyZoneC.point[List], WeeklyZoneC)
  private val weeklyDomesticReads: SubPlanReads[WeeklyDomestic] = findProduct(_.weeklyDomestic.point[List], WeeklyDomestic)
  private val weeklyRestOfWorldReads: SubPlanReads[WeeklyRestOfWorld] = findProduct(_.weeklyRestOfWorld.point[List], WeeklyRestOfWorld)

  implicit val productReads: SubPlanReads[Product] = new SubPlanReads[Product] {
    override def read(p: ProductIds, z: SubscriptionZuoraPlan, c: CatalogZuoraPlan): ValidationNel[String, Product] =
      (contentSubscriptionReads.read(p, z, c) orElse2
        membershipReads.read(p, z, c) orElse2
        contributionReads.read(p, z, c)).withTrace("productReads")
  }

  private val contentSubscriptionReads: SubPlanReads[ContentSubscription] = new SubPlanReads[ContentSubscription] {
    override def read(p: ProductIds, z: SubscriptionZuoraPlan, c: CatalogZuoraPlan): ValidationNel[String, ContentSubscription] =
      (paperReads.read(p, z, c) orElse2
        zDigipackReads.read(p, z, c) orElse2
        supporterPlusReads.read(p, z, c)).withTrace("contentSubscriptionReads")
  }

  private val paperReads: SubPlanReads[Paper] = new SubPlanReads[Paper] {
    override def read(p: ProductIds, z: SubscriptionZuoraPlan, c: CatalogZuoraPlan): ValidationNel[String, Paper] =
      (voucherReads.read(p, z, c).map(identity[Paper]) orElse2
        digitalVoucherReads.read(p, z, c) orElse2
        deliveryReads.read(p, z, c) orElse2
        nationalDeliveryReads.read(p, z, c) orElse2
        weeklyZoneAReads.read(p, z, c) orElse2
        weeklyZoneBReads.read(p, z, c) orElse2
        weeklyZoneCReads.read(p, z, c) orElse2
        weeklyDomesticReads.read(p, z, c) orElse2
        weeklyRestOfWorldReads.read(p, z, c)).withTrace("paperReads")
  }

  implicit def anyPlanReads[P <: Product, C <: ChargeList](implicit
      productReads: SubPlanReads[P],
      chargeListReads: ChargeListReads[C],
  ): SubPlanReads[SubscriptionPlan[P, C]] =
    new SubPlanReads[SubscriptionPlan[P, C]] {
      override def read(
          ids: ProductIds,
          subZuoraPlan: SubscriptionZuoraPlan,
          catZuoraPlan: CatalogZuoraPlan,
      ): ValidationNel[String, SubscriptionPlan[P, C]] =
        (for {
          charges <- chargeListReads.read(catZuoraPlan.benefits, subZuoraPlan.charges.list.toList)
          product <- productReads.read(ids, subZuoraPlan, catZuoraPlan)
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
        }).withTrace("paidPlanReads")
    }

}
