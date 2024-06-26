package com.gu.memsub.subsv2.services

import com.gu.Diff
import Diff._
import com.gu.i18n.Currency._
import com.gu.lib.DateDSL._
import com.gu.memsub.Benefit.Weekly
import com.gu.memsub.BillingPeriod.{OneYear, SixMonths, SixMonthsRecurring}
import com.gu.memsub.Product.WeeklyZoneB
import com.gu.memsub.Subscription._
import com.gu.memsub._
import com.gu.memsub.subsv2.Fixtures.productIds
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.memsub.subsv2.{Subscription => V2Subscription, _}
import com.softwaremill.diffx.generic.auto._
import org.scalatest.flatspec.AnyFlatSpec
import play.api.libs.json.JsValue
import utils.Resource
import scalaz.{Disjunction, \/, \/-}

class SubscriptionTransformTest extends AnyFlatSpec {

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
        Status.Current,
        None,
        Some("Guardian Weekly"),
      ),
      sixMonthly -> CatalogZuoraPlan(
        sixMonthly,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f95a4b48b8015a5be1206d042d") -> Weekly),
        Status.Current,
        None,
        Some("Guardian Weekly"),
      ),
      oneYear -> CatalogZuoraPlan(
        oneYear,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f958aa455e0158cf78302219f5") -> Weekly),
        Status.Current,
        None,
        Some("Guardian Weekly"),
      ),
    )
  }

  "subscription transform" should "load a one year guardian weekly subscription" in {

    val json: JsValue = Resource.getJson("rest/plans/WeeklyOneYear.json")
    val result: Disjunction[String, V2Subscription] =
      SubscriptionTransform.getSubscription(cat, Fixtures.productIds)(json)
    val expected: String \/ V2Subscription = \/-(
      V2Subscription(
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
        plans = CovariantNonEmptyList(
          RatePlan(
            id = RatePlanId("2c92c0f85bae511e015bcead96a569e5"),
            productRatePlanId = ProductRatePlanId("2c92c0f9585841e7015862c9128e153b"),
            name = "foo",
            description = "",
            productName = "Guardian Weekly Zone B",
            lastChangeType = None,
            productType = "Guardian Weekly",
            product = WeeklyZoneB,
            features = List(),
            charges = RatePlanCharge(
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
    Diff.assertEquals(expected, result)
  }

  "subscription transform" should "load a 6 month guardian weekly subscription" in {

    val json: JsValue = Resource.getJson("rest/plans/WeeklySixMonths.json")
    val result: Disjunction[String, V2Subscription] =
      SubscriptionTransform.getSubscription(cat, Fixtures.productIds)(json)

    val expected: Disjunction[String, V2Subscription] = \/-(
      V2Subscription(
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
        plans = CovariantNonEmptyList(
          RatePlan(
            id = RatePlanId("2c92c0f95bae6218015bceaf24520fa9"),
            productRatePlanId = ProductRatePlanId("2c92c0f85a4b3a23015a5be3fc2271ad"),
            name = "foo",
            description = "",
            productName = "Guardian Weekly Zone B",
            lastChangeType = None,
            productType = "Guardian Weekly",
            product = WeeklyZoneB,
            features = List(),
            charges = RatePlanCharge(
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

    Diff.assertEquals(expected, result)
  }

  "subscription transform" should "load a 6 monthly recurring guardian weekly subscription" in {

    val json: JsValue = Resource.getJson("rest/plans/WeeklySixMonthly.json")
    val result: Disjunction[String, V2Subscription] =
      SubscriptionTransform.getSubscription(cat, Fixtures.productIds)(json)
    val expected: Disjunction[String, V2Subscription] = \/-(
      V2Subscription(
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
        plans = CovariantNonEmptyList(
          RatePlan(
            id = RatePlanId("2c92c0f85be67835015be8f3743a7f8e"),
            productRatePlanId = ProductRatePlanId("2c92c0f95a4b48b8015a5be1205d042b"),
            name = "foo",
            description = "",
            productName = "Guardian Weekly Zone B",
            lastChangeType = None,
            productType = "Guardian Weekly",
            product = WeeklyZoneB,
            features = List(),
            charges = RatePlanCharge(
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

    Diff.assertEquals(expected, result)
  }

}
