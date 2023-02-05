package memsub.subsv2.services

import com.gu.i18n.Currency._
import memsub.subsv2.Fixtures
import memsub.subsv2.Fixtures.productIds
import models.subscription.Benefit.Weekly
import models.subscription.BillingPeriod.{OneYear, SixMonths, SixMonthsRecurring}
import models.subscription.Product.WeeklyZoneB
import models.subscription.Subscription.{AccountId, Id, Name, ProductRatePlanChargeId, ProductRatePlanId, RatePlanId, SubscriptionRatePlanChargeId}
import models.subscription.subsv2.ReaderType.Direct
import models.subscription.subsv2.SubscriptionPlan.ContentSubscription
import models.subscription.subsv2.{CatalogZuoraPlan, CovariantNonEmptyList, PaidCharge, PaidSubscriptionPlan, Subscription}
import models.subscription.{Benefit, BillingPeriod, Price, PricingSummary, Product, Status}
import org.specs2.mutable.Specification
import play.api.libs.json.JsValue
import scalaz.{Disjunction, \/, \/-}
import services.{SubIds, SubscriptionTransform}
import util.Resource
import utils.DateDSL.IntOps

class SubscriptionTransformTest extends Specification {

  "subscription transform" should {
    "turn a supporter with discount into a list with both rateplans" in {
      val json: JsValue = Resource.getJson("rest/SupporterDiscountUat.json")
      val result: String \/ List[SubIds] = SubscriptionTransform.backdoorRatePlanIdsFromJson(json).map(_.sortBy(_.ratePlanId.get))
      val expected: String \/ List[SubIds] = \/-(
        List(
          SubIds(RatePlanId("2c92c0f857db5bf20157dd3418114c3f"), ProductRatePlanId("2c92c0f953078a5601531299dae54a4d")),
          SubIds(RatePlanId("2c92c0f857db5bf20157dd34182b4c44"), ProductRatePlanId("2c92c0f84c5100b6014c569b83b33ebd")),
        ),
      )

      result mustEqual expected
    }
  }

  val cat = {
    val sixMonths = ProductRatePlanId("2c92c0f85a4b3a23015a5be3fc2271ad")
    val sixMonthly = ProductRatePlanId("2c92c0f95a4b48b8015a5be1205d042b")
    val oneYear = ProductRatePlanId("2c92c0f9585841e7015862c9128e153b")
    Map[ProductRatePlanId, CatalogZuoraPlan](
      sixMonths -> CatalogZuoraPlan(
        sixMonths,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f85a4b3a23015a5be3fc4471af") -> Weekly),
        Status.current,
        None,
      ),
      sixMonthly -> CatalogZuoraPlan(
        sixMonthly,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f95a4b48b8015a5be1206d042d") -> Weekly),
        Status.current,
        None,
      ),
      oneYear -> CatalogZuoraPlan(
        oneYear,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f958aa455e0158cf78302219f5") -> Weekly),
        Status.current,
        None,
      ),
    )
  }

  "subscription transform" should {
    "load a one year guardian weekly subscription" in {

      val json: JsValue = Resource.getJson("rest/plans/WeeklyOneYear.json")
      val result: Disjunction[String, Subscription[ContentSubscription]] =
        SubscriptionTransform.getSubscription[ContentSubscription](cat, Fixtures.productIds, () => 3 May 2017)(json)
      val expected: String \/ Subscription[ContentSubscription] = \/-(
        Subscription[PaidSubscriptionPlan[Product.WeeklyZoneB.type, PaidCharge[Weekly.type, BillingPeriod.OneYear.type]]](
          id = Id("2c92c0f85bae511e015bcead968f69e0"),
          name = Name("A-S00069184"),
          accountId = AccountId("2c92c0f859b047b70159bc8dcc901253"),
          startDate = 1 May 2017,
          acceptanceDate = 1 May 2017,
          termStartDate = 1 May 2017,
          termEndDate = 1 May 2018,
          casActivationDate = None,
          promoCode = None,
          isCancelled = false,
          hasPendingFreePlan = false,
          plans = CovariantNonEmptyList(
            PaidSubscriptionPlan(
              id = RatePlanId("2c92c0f85bae511e015bcead96a569e5"),
              productRatePlanId = ProductRatePlanId("2c92c0f9585841e7015862c9128e153b"),
              name = "foo",
              description = "",
              productName = "Guardian Weekly Zone B",
              product = WeeklyZoneB,
              features = List(),
              charges = PaidCharge(
                Benefit.Weekly,
                OneYear,
                PricingSummary(Map(GBP -> Price(152.0f, GBP))),
                ProductRatePlanChargeId("2c92c0f958aa455e0158cf78302219f5"),
                SubscriptionRatePlanChargeId("2c92c0f85bae511e015bcead96b269e6"),
              ),
              chargedThrough = None,
              start = 1 May 2017,
              end = 1 May 2018,
            ),
            Nil,
          ),
          readerType = Direct,
          autoRenew = false,
          gifteeIdentityId = None,
        ),
      )
      result mustEqual expected
    }
  }

  "subscription transform" should {
    "load a 6 month guardian weekly subscription" in {

      val json: JsValue = Resource.getJson("rest/plans/WeeklySixMonths.json")
      val result: Disjunction[String, Subscription[ContentSubscription]] =
        SubscriptionTransform.getSubscription[ContentSubscription](cat, Fixtures.productIds, () => 3 May 2017)(json)

      val expected: Disjunction[String, Subscription[ContentSubscription]] = \/-(
        Subscription[PaidSubscriptionPlan[Product.WeeklyZoneB.type, PaidCharge[Weekly.type, BillingPeriod.SixMonths.type]]](
          id = Id("2c92c0f95bae6218015bceaf243d0fa4"),
          name = Name("A-S00069185"),
          accountId = AccountId("2c92c0f859b047b70159bc8dcc901253"),
          startDate = 1 May 2017,
          acceptanceDate = 1 May 2017,
          termStartDate = 1 May 2017,
          termEndDate = 1 May 2018,
          casActivationDate = None,
          promoCode = None,
          isCancelled = false,
          hasPendingFreePlan = false,
          plans = CovariantNonEmptyList(
            PaidSubscriptionPlan(
              id = RatePlanId("2c92c0f95bae6218015bceaf24520fa9"),
              productRatePlanId = ProductRatePlanId("2c92c0f85a4b3a23015a5be3fc2271ad"),
              name = "foo",
              description = "",
              productName = "Guardian Weekly Zone B",
              product = WeeklyZoneB,
              features = List(),
              charges = PaidCharge(
                Benefit.Weekly,
                SixMonths,
                PricingSummary(Map(GBP -> Price(76.0f, GBP))),
                ProductRatePlanChargeId("2c92c0f85a4b3a23015a5be3fc4471af"),
                SubscriptionRatePlanChargeId("2c92c0f95bae6218015bceaf245f0faa"),
              ),
              chargedThrough = None,
              start = 1 May 2017,
              end = 1 Nov 2017,
            ),
            Nil,
          ),
          readerType = Direct,
          autoRenew = false,
          gifteeIdentityId = None,
        ),
      )

      result mustEqual expected
    }
  }

  "subscription transform" should {
    "load a 6 monthly recurring guardian weekly subscription" in {

      val json: JsValue = Resource.getJson("rest/plans/WeeklySixMonthly.json")
      val result: Disjunction[String, Subscription[ContentSubscription]] =
        SubscriptionTransform.getSubscription[ContentSubscription](cat, Fixtures.productIds, () => 3 May 2017)(json)
      val expected: Disjunction[String, Subscription[ContentSubscription]] = \/-(
        Subscription[PaidSubscriptionPlan[Product.WeeklyZoneB.type, PaidCharge[Weekly.type, BillingPeriod.SixMonthsRecurring.type]]](
          id = Id("2c92c0f85be67835015be8f374217f86"),
          name = Name("A-S00069279"),
          accountId = AccountId("2c92c0f859b047b70159bc8dcc901253"),
          startDate = 1 May 2017,
          acceptanceDate = 1 May 2017,
          termStartDate = 1 May 2017,
          termEndDate = 1 May 2018,
          casActivationDate = None,
          promoCode = None,
          isCancelled = false,
          hasPendingFreePlan = false,
          plans = CovariantNonEmptyList(
            PaidSubscriptionPlan(
              id = RatePlanId("2c92c0f85be67835015be8f3743a7f8e"),
              productRatePlanId = ProductRatePlanId("2c92c0f95a4b48b8015a5be1205d042b"),
              name = "foo",
              description = "",
              productName = "Guardian Weekly Zone B",
              product = WeeklyZoneB,
              features = List(),
              charges = PaidCharge(
                Benefit.Weekly,
                SixMonthsRecurring,
                PricingSummary(Map(GBP -> Price(76.0f, GBP))),
                ProductRatePlanChargeId("2c92c0f95a4b48b8015a5be1206d042d"),
                SubscriptionRatePlanChargeId("2c92c0f85be67835015be8f374497f93"),
              ),
              chargedThrough = None,
              start = 1 May 2017,
              end = 1 May 2018,
            ),
            Nil,
          ),
          readerType = Direct,
          autoRenew = true,
          gifteeIdentityId = None,
        ),
      )

      result mustEqual expected
    }
  }

}
