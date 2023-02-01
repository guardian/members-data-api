package models.subscription.promo

import configuration.DiscountRatePlanIds
import models.subscription.Subscription.ProductRatePlanId
import models.subscription.BillingPeriod
import subscriptions.Discounter
import org.joda.time.{DateTime, LocalDate}
import services.zuora.soap.models.Commands.{Amend, RatePlan, Renew, Subscribe}

trait PromotionApplicator[T <: PromoContext, A] {
  type PlanFinder = ProductRatePlanId => BillingPeriod
  def apply(valid: ValidPromotion[T], planFinder: PlanFinder, discountRatePlanIds: DiscountRatePlanIds)(a: A): A
}

object PromotionApplicator {

  private def discount(p: PercentDiscount, d: DiscountRatePlanIds, bp: BillingPeriod): RatePlan =
    new Discounter(d).getDiscountRatePlan(bp, p)

  implicit object AmendPromoApplicator extends PromotionApplicator[Upgrades, Amend] {
    def apply(valid: ValidPromotion[Upgrades], p: PlanFinder, d: DiscountRatePlanIds)(a: Amend): Amend = (valid.promotion.promotionType match {
      case DoubleType(o, t) =>
        (
          apply(valid.copy(promotion = valid.promotion.copy(promotionType = o)), p, d) _ andThen
            apply(valid.copy(promotion = valid.promotion.copy(promotionType = t)), p, d)
        )(a)
      case o: PercentDiscount =>
        a.copy(newRatePlans = a.newRatePlans.<::(discount(o, d, p(ProductRatePlanId(a.newRatePlans.head.productRatePlanId)))))
      case _: Incentive | Tracking => a // avoid _ here to get reminders to explicitly handle new promotion types
    }).copy(promoCode = Some(valid.code))
  }

  implicit object SubscribePromoApplicator extends PromotionApplicator[NewUsers, Subscribe] {
    def apply(valid: ValidPromotion[NewUsers], p: PlanFinder, d: DiscountRatePlanIds)(a: Subscribe): Subscribe =
      (valid.promotion.promotionType match {
        case DoubleType(o, t) =>
          (
            apply(valid.copy(promotion = valid.promotion.copy(promotionType = o)), p, d) _ andThen
              apply(valid.copy(promotion = valid.promotion.copy(promotionType = t)), p, d)
          )(a)
        case o: PercentDiscount => a.copy(ratePlans = a.ratePlans.<::(discount(o, d, p(ProductRatePlanId(a.ratePlans.head.productRatePlanId)))))
        case FreeTrial(duration) => a.copy(contractAcceptance = a.contractEffective.plusDays(duration.getDays))
        case _: Incentive | Tracking => a
      }).copy(promoCode = Some(valid.code))
  }

  implicit object RenewPromoApplicator extends PromotionApplicator[Renewal, Renew] {
    def apply(valid: ValidPromotion[Renewal], p: PlanFinder, d: DiscountRatePlanIds)(renewCommand: Renew): Renew =
      (valid.promotion.promotionType match {
        case DoubleType(o, t) =>
          (
            apply(valid.copy(promotion = valid.promotion.copy(promotionType = o)), p, d) _ andThen
              apply(valid.copy(promotion = valid.promotion.copy(promotionType = t)), p, d)
          )(renewCommand)
        case o: PercentDiscount =>
          renewCommand.copy(newRatePlans =
            renewCommand.newRatePlans.<::(discount(o, d, p(ProductRatePlanId(renewCommand.newRatePlans.head.productRatePlanId)))),
          )
        case _: Incentive | Tracking | Retention => renewCommand
      }).copy(promoCode = Some(valid.code))
  }

}
