package com.gu.memsub.services
import com.gu.i18n.Country
import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub.promo.Promotion.AnyPromotion
import com.gu.memsub.promo._
import com.gu.subscriptions.Discounter

import scalaz._
import scalaz.syntax.std.option._
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}

class PromoService(promos: PromotionCollection, discounter: Discounter)(implicit ec: ExecutionContext) {
  import LogImplicit._

  def findPromotion(promoCode: PromoCode): Option[AnyPromotion] = {
    promos.all.find(_.codes.filter(_.get equalsIgnoreCase promoCode.get).nonEmpty).withLogging("found promotion")

  }

  def findPromotionFuture(promoCode: PromoCode): Future[Option[AnyPromotion]] = {
    promos.futureAll.map { promos =>
      promos.find(_.codes.filter(_.get equalsIgnoreCase promoCode.get).nonEmpty).withLogging("found promotion")
    }
  }

  /**
    * Validate a promotion
    */
  def validate[C >: Both <: PromoContext](promoCode: PromoCode, country: Country, prpId: ProductRatePlanId, now: DateTime = DateTime.now)
                                 (implicit validator: PromotionValidator[C]): PromoError \/ ValidPromotion[C] = {
    for {
      promo <- (findPromotion(promoCode) \/> (NoSuchCode: PromoError)).withLogging("found promo")
      matchedPromoType <- validator.validate(promo, prpId, country).withLogging("validated promo type")
    } yield ValidPromotion(promoCode, promo.copy(promotionType = matchedPromoType))
  }

  /**
    * Validate lots of promo codes
    * If all the promo codes are None, a Right of None will be returned
    * otherwise the first successful promo code or error will come back
    */
  def validateMany[C >: Both <: PromoContext](country: Country, prpId: ProductRatePlanId, now: DateTime = DateTime.now)(codes: Option[PromoCode]*)
                                     (implicit matcher: PromotionValidator[C]): PromoError \/ Option[ValidPromotion[C]] = {

    codes.flatMap(_.toSeq).map(validate[C](_, country, prpId)).reduceOption(_ orElse _)
      .fold[PromoError \/ Option[ValidPromotion[C]]](\/.right(None))(_.map(Some(_)))
  }
}
