package memsub.promo

import com.github.nscala_time.time.Imports._
import com.gu.i18n.Country.{UK, US}
import configuration.ids.{DigitalPackRatePlanIds, ProductFamilyRatePlanIds}
import memsub.promo.PromotionStub.promoFor
import models.subscription.Subscription.ProductRatePlanId
import models.subscription.Subscriptions
import models.subscription.promo.{
  ExpiredPromotion,
  FreeTrial,
  InvalidCountry,
  InvalidProductRatePlan,
  PromoError,
  PromotionNotActiveYet,
  Retention,
  Tracking,
}
import org.joda.time.Days
import org.specs2.mutable.Specification
import scalaz.\/
import scalaz.syntax.either._

class PromotionTest extends Specification {
  val config = ProductFamilyRatePlanIds.config()("DEV", Subscriptions)
  val prpIds = DigitalPackRatePlanIds.fromConfig(config)

  def generatePromotion(starts: DateTime, expires: DateTime) =
    promoFor("TEST01", prpIds.digitalPackMonthly, prpIds.digitalPackYearly, prpIds.digitalPackQuaterly).copy(
      starts = starts,
      expires = Some(expires),
      promotionType = FreeTrial(Days.days(5)),
    )

  "Promotion" should {
    val thisMorning = DateTime.now().withTimeAtStartOfDay()
    val yesterdayMorning = thisMorning.minusDays(1)
    val tomorrowMorning = thisMorning.plusDays(1)

    val expiredPromotion = generatePromotion(yesterdayMorning, thisMorning)
    val activePromotion = generatePromotion(yesterdayMorning, tomorrowMorning)
    val futurePromotion = generatePromotion(tomorrowMorning, tomorrowMorning.plusDays(1))

    val yearlyRatePlan = prpIds.digitalPackYearly
    val invalidRatePlan = ProductRatePlanId("other-id")
    val providedStarts = futurePromotion.starts
    val providedExpires = futurePromotion.expires.get

    "#validateFor" in {

      // check invalid exceptions take precience over time/status-based exceptions

      expiredPromotion.validateFor(invalidRatePlan, UK) must_=== \/.left[PromoError, Unit](InvalidProductRatePlan)
      expiredPromotion.validateFor(yearlyRatePlan, US) must_=== \/.left[PromoError, Unit](InvalidCountry)
      activePromotion.validateFor(invalidRatePlan, UK) must_=== \/.left[PromoError, Unit](InvalidProductRatePlan)
      activePromotion.validateFor(yearlyRatePlan, US) must_=== \/.left[PromoError, Unit](InvalidCountry)
      futurePromotion.validateFor(invalidRatePlan, UK) must_=== \/.left[PromoError, Unit](InvalidProductRatePlan)
      futurePromotion.validateFor(yearlyRatePlan, US) must_=== \/.left[PromoError, Unit](InvalidCountry)

      // check valid promotions adhere to their time/status.

      expiredPromotion.validateFor(yearlyRatePlan, UK) must_=== \/.left[PromoError, Unit](ExpiredPromotion)
      activePromotion.validateFor(yearlyRatePlan, UK) must_=== ().right
      futurePromotion.validateFor(yearlyRatePlan, UK) must_=== \/.left[PromoError, Unit](PromotionNotActiveYet)

      // check start date comparison is inclusive to the millisecond, and expires is exclusive to the millisecond
      // (tested via the provided 'now' overrride)

      futurePromotion.validateFor(yearlyRatePlan, UK, providedStarts.minusMillis(1)) must_=== \/.left[PromoError, Unit](PromotionNotActiveYet)
      futurePromotion.validateFor(yearlyRatePlan, UK, providedExpires) must_=== \/.left[PromoError, Unit](ExpiredPromotion)
      futurePromotion.validateFor(yearlyRatePlan, UK, providedStarts) must_=== ().right
      futurePromotion.validateFor(yearlyRatePlan, UK, providedExpires.minusMillis(1)) must_=== ().right

      // check Tracking promotions don't check against the rate plan InvalidProductRatePlan
      activePromotion.copy(promotionType = Tracking).validateFor(invalidRatePlan, UK) must_=== ().right
      activePromotion.copy(promotionType = Tracking).validateFor(yearlyRatePlan, UK) must_=== ().right
      expiredPromotion.copy(promotionType = Tracking).validateFor(invalidRatePlan, US) must_=== \/.left[PromoError, Unit](InvalidCountry)

      // check Retention promotions check against the rate plan InvalidProductRatePlan
      activePromotion.copy(promotionType = Retention).validateFor(invalidRatePlan, UK) must_=== \/.left[PromoError, Unit](InvalidProductRatePlan)
      expiredPromotion.copy(promotionType = Retention).validateFor(yearlyRatePlan, US) must_=== \/.left[PromoError, Unit](InvalidCountry)
    }
  }

}
