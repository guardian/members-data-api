package models.subscription.promo

import com.gu.i18n.Country
import models.subscription.Subscription.ProductRatePlanId
import models.subscription.promo.Promotion.AnyPromotion

import scalaz.syntax.applicative._
import scalaz.{NonEmptyList, \/}

// the promotion matcher is to satisfy the compiler that the promotion is applicable to the given situation, product and country
trait PromotionValidator[T <: PromoContext] {
  def validate(p: AnyPromotion, prpId: ProductRatePlanId, country: Country): PromoError \/ PromotionType[T]
}

object PromotionValidator {

  import LogImplicit._

  implicit object RenewalPromoValidator$ extends PromotionValidator[Renewal] {

    private def validateEffectivePromotionType(
        promotion: AnyPromotion,
        prpId: ProductRatePlanId,
        country: Country,
        promotionType: PromotionType[Renewal],
    ): PromoError \/ PromotionType[Renewal] = {
      val errors = promotion.validateAll(Some(prpId), country)
      if (errors.exists(_ != InvalidProductRatePlan))
        \/.l[PromotionType[Renewal]](errors.head)
      else if (errors.contains(InvalidProductRatePlan))
        \/.r[PromoError](Tracking)
      else \/.r[PromoError](promotionType)
    }

    def validate(promotion: AnyPromotion, prpId: ProductRatePlanId, country: Country) = {
      for {
        promoTypeForContext <- validateForContext(promotion.promotionType).withLogging("upgrade/renewal for context")
        promoTypeForProductRatePlan <- validateEffectivePromotionType(promotion, prpId, country, promoTypeForContext).withLogging(
          "effective promotion type",
        )
      } yield promoTypeForProductRatePlan

    }

    private def validateForContext(p: PromotionType[PromoContext]): PromoError \/ PromotionType[Renewal] = p match {
      case Tracking => \/.r[PromoError](Tracking: PromotionType[Renewal])
      case Retention => \/.r[PromoError](Retention: PromotionType[Renewal])
      case _: FreeTrial => \/.l[PromotionType[Renewal]](NotApplicable)
      case o: Incentive => \/.r[PromoError](o)
      case o: PercentDiscount => \/.r[PromoError](o)
      case DoubleType(a, b) => (validateForContext(a) |@| validateForContext(b))(DoubleType[Renewal])
    }
  }

  implicit object UpgradePromoValidator$ extends PromotionValidator[Upgrades] {

    def validate(promo: AnyPromotion, prpId: ProductRatePlanId, country: Country) = {
      for {
        promoTypeForContext <- validateForContext(promo.promotionType)
        _ <- promo.validateAll(Some(prpId), country) match {
          case head :: _ => \/.l[PromotionType[Upgrades]](head)
          case Nil => \/.r[PromoError](())
        }
      } yield promoTypeForContext
    }

    private def validateForContext(p: PromotionType[PromoContext]): PromoError \/ PromotionType[Upgrades] = p match {
      case Tracking => \/.r[PromoError](Tracking)
      case Retention => \/.l[PromotionType[Upgrades]](NotApplicable)
      case _: FreeTrial => \/.l[PromotionType[Upgrades]](NotApplicable)
      case o: Incentive => \/.r[PromoError](o)
      case o: PercentDiscount => \/.r[PromoError](o)
      case DoubleType(a, b) => (validateForContext(a) |@| validateForContext(b))(DoubleType[Upgrades])
    }
  }

  implicit object SubscribePromoValidator$ extends PromotionValidator[NewUsers] {

    def validate(promo: AnyPromotion, prpId: ProductRatePlanId, country: Country) = {
      for {
        promoTypeForContext <- validateForContext(promo.promotionType)
        _ <- promo.validateAll(Some(prpId), country) match {
          case head :: _ => \/.l[PromotionType[NewUsers]](head)
          case Nil => \/.r[PromoError](())
        }
      } yield promoTypeForContext
    }

    private def validateForContext(p: PromotionType[PromoContext]): PromoError \/ PromotionType[NewUsers] = p match {
      case Retention => \/.l[PromotionType[NewUsers]](NotApplicable)
      case Tracking => \/.r[PromoError](Tracking)
      case f: FreeTrial => \/.r[PromoError](f)
      case o: Incentive => \/.r[PromoError](o)
      case o: PercentDiscount => \/.r[PromoError](o)
      case DoubleType(a, b) => (validateForContext(a) |@| validateForContext(b))(DoubleType[NewUsers])
    }
  }
}
