package com.gu.memsub.services
import com.gu.config.{DiscountRatePlan, DiscountRatePlanIds}
import com.gu.i18n.Country._
import com.gu.i18n.Currency._
import com.gu.i18n.Country
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub.promo._
import com.gu.salesforce.ContactId
import com.gu.subscriptions.Discounter
import org.joda.time.{Days, LocalDate}
import org.specs2.mutable.Specification
import scalaz.NonEmptyList
import scalaz.syntax.std.option._
import com.gu.memsub.{Address, BillingPeriod, FullName}
import com.gu.memsub.promo.PromotionStub._
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.zuora.api.{DefaultGateway, StripeUKMembershipGateway}
import com.gu.zuora.soap.models.Commands._

import scala.concurrent.ExecutionContext.Implicits.global

class PromoServiceTest extends Specification {

  val discountPrpId = ProductRatePlanId("discount")
  val discountPrpChargeId = ProductRatePlanChargeId("discount")
  val discountIds = DiscountRatePlanIds(DiscountRatePlan(discountPrpId, discountPrpChargeId))
  val discounter = new Discounter(discountIds)

  val freePrpId = ProductRatePlanId("Free")
  val paidPrpId = ProductRatePlanId("Paid")

  val planCatalog = Map[ProductRatePlanId, BillingPeriod](
    freePrpId -> BillingPeriod.Month,
    paidPrpId -> BillingPeriod.Month,
  )

  val upgrade = Amend("sub ID", Seq(freePrpId.get), NonEmptyList(RatePlan(paidPrpId.get, None)))
  val capitalPromo = promoFor("UPPERCASE", paidPrpId).ofType(PercentDiscount(None, 100))
  val lowerPromo = promoFor("lowercase", paidPrpId).ofType(PercentDiscount(None, 100))
  val trialPromo = promoFor("trial", paidPrpId).ofType(FreeTrial(Days.days(28)))
  val service = new PromoService(StaticPromotionCollection(capitalPromo, lowerPromo, trialPromo), discounter)

  /** Adapt this test to use the new code
    */
  def applyPromotion[A >: Both <: PromoContext, B](command: B, code: Option[PromoCode], country: Option[Country])(implicit
      matcher: PromotionValidator[A],
      applicator: PromotionApplicator[A, B],
  ): B = {

    service
      .validateMany[A](country.getOrElse(UK), ProductRatePlanId(upgrade.newRatePlans.head.productRatePlanId))(code)
      .map(
        _.map(
          applicator.apply(_, planCatalog(_), discountIds)(command),
        ).getOrElse(command),
      )
      .fold[B](_ => command, identity)
  }

  "PromoService" should {
    "Find promotions case-insensitively" in {
      service.findPromotion(PromoCode("UPPERCASE")) mustEqual Some(capitalPromo)
      service.findPromotion(PromoCode("uppercase")) mustEqual Some(capitalPromo)
      service.findPromotion(PromoCode("lowercase")) mustEqual Some(lowerPromo)
      service.findPromotion(PromoCode("LOWERCASE")) mustEqual Some(lowerPromo)
    }
  }

  "PromoService's applyPromotion[Upgrades, Amend] method" should {

    "Do nothing when given no promocode" in {
      applyPromotion[Upgrades, Amend](upgrade, None, UK.some) mustEqual upgrade
    }

    "Do nothing when the promo code is invalid" in {
      applyPromotion[Upgrades, Amend](upgrade, Some(PromoCode("nope")), UK.some) mustEqual upgrade
    }

    "Do nothing when the country is invalid" in {
      applyPromotion[Upgrades, Amend](upgrade, Some(capitalPromo.codes.head), US.some) mustEqual upgrade
    }

    "Assume UK when no country is supplied as most users will be UK users" in {
      applyPromotion[Upgrades, Amend](upgrade, capitalPromo.codes.head.some, None).promoCode mustEqual capitalPromo.codes.head.some
    }

    "Apply a discount rate plan of some kind when the promo is valid" in {
      applyPromotion[Upgrades, Amend](upgrade, capitalPromo.codes.head.some, UK.some).newRatePlans.list.length mustEqual 2
      applyPromotion[Upgrades, Amend](upgrade, capitalPromo.codes.head.some, UK.some).promoCode mustEqual capitalPromo.codes.head.some
    }

    "Do nothing when given a free trial for an upgrade" in {
      applyPromotion[Upgrades, Amend](upgrade, trialPromo.codes.head.some, UK.some) mustEqual upgrade
    }

    "Change the payment contract acceptance date when given a free trial for a Subscribe" in {

      val contact = new ContactId {
        override def salesforceContactId: String = "SFCID"
        override def salesforceAccountId: String = "SFAID"
      }

      val name = new FullName {
        override def first: String = "Fred"
        override def last: String = "Subscriber"
        override def title = None
      }

      val address = Address("123", "Fake St", "Faketown", "Kent", "1234", "GB")
      val account = Account(contact, "12345", GBP, autopay = false, paymentGateway = DefaultGateway)
      val email = "test-email-123@thegulocal.com"
      val janFirst = new LocalDate(2016, 1, 1)
      val janSecond = new LocalDate(2016, 1, 2)
      val subscribe = Subscribe(
        account = account,
        ratePlans = NonEmptyList(RatePlan(paidPrpId.get, None)),
        paymentMethod = None,
        name = name,
        address = address,
        email = email,
        contractEffective = janFirst,
        contractAcceptance = janSecond,
        readerType = Direct,
      )

      val promoted = applyPromotion[NewUsers, Subscribe](subscribe, trialPromo.codes.head.some, UK.some)
      promoted.promoCode mustEqual trialPromo.codes.head.some
      promoted.contractAcceptance mustEqual janFirst.plusDays(28)
    }
  }

}
