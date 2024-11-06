package com.gu.memsub.subsv2.reads

import com.gu.i18n.Currency._
import com.gu.lib.DateDSL._
import com.gu.memsub.BillingPeriod.Month
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId, RatePlanId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.ReaderType.Patron
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.SubJsonReads._
import com.gu.memsub.subsv2.services.TestCatalog.catalogProd
import com.gu.memsub.{subsv2, _}
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import scalaz.NonEmptyList
import utils.Resource

class SubReadsTest extends Specification {

  "Subscription JSON reads" should {

    "Discard percentage discount rate plans when reading JSON" in {

      val plans = Resource.getJson("rest/plans/Promo.json").validate[Subscription](subscriptionReads).get.ratePlans
      plans mustEqual List(
        subsv2.RatePlan(
          id = RatePlanId("2c92c0f957220b5d01573252b3bb7c71"),
          productRatePlanId = ProductRatePlanId("2c92c0f94f2acf73014f2c908f671591"),
          productName = "Digital Pack",
          lastChangeType = None,
          features = List.empty,
          ratePlanCharges = NonEmptyList(
            RatePlanCharge(
              id = SubscriptionRatePlanChargeId("2c92c0f957220b5d01573252b3c67c72"),
              productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f94f2acf73014f2c91940a166d"),
              pricing = PricingSummary(
                Map(
                  GBP -> Price(11.99f, GBP),
                ),
              ),
              zBillingPeriod = Some(ZMonth),
              endDateCondition = SubscriptionEnd,
              upToPeriods = None,
              upToPeriodsType = None,
              chargedThroughDate = None,
              effectiveStartDate = 2 Oct 2016,
              effectiveEndDate = 16 Sep 2017,
            ),
          ),
        ),
      )
    }

    "Not discard credit rate plans when reading JSON" in {

      val actualPlans = Resource.getJson("rest/plans/Credits.json").validate[Subscription](subscriptionReads).get.ratePlans

      actualPlans must containTheSameElementsAs(PlanWithCreditsTestData.allRatePlans)
    }

    "read an echo legacy monthly subscription" in {
      // there are 5 active Quarterly which won't be readable
      val actualSubscription = Resource.getJson("rest/plans/EchoLegacy.json").validate[Subscription](subscriptionReads).get

      actualSubscription.subscriptionNumber.getNumber mustEqual "A-S00ECHO"
      actualSubscription.plan(catalogProd).product(catalogProd) mustEqual Product.Delivery
      actualSubscription.plan(catalogProd).billingPeriod.toEither mustEqual Right(Month)
    }

    "read a voucher subscription with a fixed recurring discount" in {

      val actualSubscription = Resource.getJson("rest/plans/WithRecurringFixedDiscount.json").validate[Subscription](subscriptionReads).get

      actualSubscription.subscriptionNumber.getNumber mustEqual "A-S00FIXEDDISC"
      actualSubscription.plan(catalogProd).product(catalogProd) mustEqual Product.Voucher
      actualSubscription.plan(catalogProd).billingPeriod.toEither mustEqual Right(Month)
      actualSubscription.ratePlans.filter(_.effectiveEndDate.isAfter(LocalDate.parse("2024-07-24"))) must containTheSameElementsAs(
        List(FixedDiscountRecurringTestData.mainPlan, FixedDiscountRecurringTestData.discount),
      )
    }

    "parse Patron reader type correctly from subscription" in {
      val json = Resource.getJson("rest/PatronReaderType.json")
      val subscription =
        SubJsonReads.subscriptionReads.reads(json).get

      subscription.readerType mustEqual Patron
    }
  }
}

object FixedDiscountRecurringTestData {

  val mainPlan: RatePlan = RatePlan(
    RatePlanId("withdiscountrateplanid2"),
    ProductRatePlanId("2c92a0ff56fe33f50157040bbdcf3ae4"),
    "Newspaper Voucher",
    Some("Add"),
    List(),
    NonEmptyList(
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0ff56fe33f5015709cce7ad1aea"),
        PricingSummary(Map(GBP -> Price(8.42f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0ff56fe33f5015709c80af30495"),
        PricingSummary(Map(GBP -> Price(11.45f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0ff56fe33f0015709cac4561bf3"),
        PricingSummary(Map(GBP -> Price(8.42f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0fd56fe270b015709cc16f92645"),
        PricingSummary(Map(GBP -> Price(8.42f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0fd56fe270b015709c90c291c49"),
        PricingSummary(Map(GBP -> Price(8.42f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0fd56fe26b6015709ca144a646a"),
        PricingSummary(Map(GBP -> Price(8.42f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0fd56fe26b60157042fcd462666"),
        PricingSummary(Map(GBP -> Price(11.44f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0fc56fe26ba01570418eddd26e1"),
        PricingSummary(Map(GBP -> Price(2.0f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2024-02-23"),
        LocalDate.parse("2099-09-23"),
      ),
    ),
  )

  val discount = RatePlan(
    RatePlanId("withdiscountrateplanid5"),
    ProductRatePlanId("2c92a0ff5e09bd67015e0a93efe86d2e"),
    "Discounts",
    Some("Add"),
    List(),
    NonEmptyList(
      RatePlanCharge(
        SubscriptionRatePlanChargeId("withdiscountrateplanchargeid1"),
        ProductRatePlanChargeId("2c92a0ff5e09bd67015e0a93f0026d34"),
        PricingSummary(Map(GBP -> Price(-4.34f, GBP))),
        Some(ZMonth),
        None,
        SubscriptionEnd,
        None,
        None,
        Some(LocalDate.parse("2099-08-23")),
        LocalDate.parse("2017-08-23"),
        LocalDate.parse("2099-09-23"),
      ),
    ),
  )

}

object PlanWithCreditsTestData {

  private val gwCharge = RatePlanCharge(
    id = SubscriptionRatePlanChargeId("8ad093fb90a5fb8c0190bdfd67d862ae"),
    productRatePlanChargeId = ProductRatePlanChargeId("8ad097b48ff26452019001d46f8824e2"),
    pricing = PricingSummary(Map(GBP -> Price(15f, GBP))),
    zBillingPeriod = Some(ZMonth),
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
    chargedThroughDate = Some(28 Jul 2024),
    effectiveStartDate = 28 Jun 2024,
    effectiveEndDate = 13 Jun 2025,
  )
  private val sPlusCharge = RatePlanCharge(
    id = SubscriptionRatePlanChargeId("8ad093fb90a5fb8c0190bdfd67d862b0"),
    productRatePlanChargeId = ProductRatePlanChargeId("8ad097b48ff26452019001d78ee325d1"),
    pricing = PricingSummary(Map(GBP -> Price(10f, GBP))),
    zBillingPeriod = Some(ZMonth),
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
    chargedThroughDate = Some(28 Jul 2024),
    effectiveStartDate = 28 Jun 2024,
    effectiveEndDate = 13 Jun 2025,
  )
  val mainPlan = subsv2.RatePlan(
    id = RatePlanId("8ad093fb90a5fb8c0190bdfd67d662ac"),
    productRatePlanId = ProductRatePlanId("8ad097b48ff26452019001cebac92376"),
    productName = "Tier Three",
    lastChangeType = None,
    features = List.empty,
    ratePlanCharges = NonEmptyList(gwCharge, sPlusCharge),
  )
  private val holCharge = RatePlanCharge(
    id = SubscriptionRatePlanChargeId("8ad093fb90a5fb8c0190bdfd67d362a9"),
    productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f96b03800b016b081fc0f41bb4"),
    pricing = PricingSummary(Map(GBP -> Price(-3.75f, GBP))),
    zBillingPeriod = None,
    endDateCondition = OneTime,
    upToPeriods = None,
    upToPeriodsType = None,
    chargedThroughDate = None,
    effectiveStartDate = 28 Jul 2024,
    effectiveEndDate = 29 Jul 2024,
  )
  private val discountPlan = subsv2.RatePlan(
    id = RatePlanId("8ad093fb90a5fb8c0190bdfd67d062a7"),
    productRatePlanId = ProductRatePlanId("2c92c0f96b03800b016b081fc04f1ba2"),
    productName = "Discounts",
    lastChangeType = Some("Add"),
    features = List.empty,
    ratePlanCharges = NonEmptyList(holCharge),
  )

  val allRatePlans = List(mainPlan, discountPlan)
}
