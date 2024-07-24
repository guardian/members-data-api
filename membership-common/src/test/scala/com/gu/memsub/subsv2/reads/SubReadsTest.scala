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
          chargedThroughDate = None,
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
            ),
          ),
          start = 2 Oct 2016,
          end = 16 Sep 2017,
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

      actualSubscription.name.get mustEqual ("A-S00ECHO")
      actualSubscription.plan(catalogProd).product(catalogProd) mustEqual Product.Delivery
      actualSubscription.plan(catalogProd).billingPeriod.toEither mustEqual Right(Month)
    }

    "parse Patron reader type correctly from subscription" in {
      val json = Resource.getJson("rest/PatronReaderType.json")
      val subscription =
        SubJsonReads.subscriptionReads.reads(json).get

      subscription.readerType mustEqual Patron
    }
  }
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
  )
  private val sPlusCharge = RatePlanCharge(
    id = SubscriptionRatePlanChargeId("8ad093fb90a5fb8c0190bdfd67d862b0"),
    productRatePlanChargeId = ProductRatePlanChargeId("8ad097b48ff26452019001d78ee325d1"),
    pricing = PricingSummary(Map(GBP -> Price(10f, GBP))),
    zBillingPeriod = Some(ZMonth),
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
  )
  val mainPlan = subsv2.RatePlan(
    id = RatePlanId("8ad093fb90a5fb8c0190bdfd67d662ac"),
    productRatePlanId = ProductRatePlanId("8ad097b48ff26452019001cebac92376"),
    productName = "Tier Three",
    lastChangeType = None,
    features = List.empty,
    chargedThroughDate = Some(28 Jul 2024),
    ratePlanCharges = NonEmptyList(gwCharge, sPlusCharge),
    start = 28 Jun 2024,
    end = 13 Jun 2025,
  )
  private val holCharge = RatePlanCharge(
    id = SubscriptionRatePlanChargeId("8ad093fb90a5fb8c0190bdfd67d362a9"),
    productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f96b03800b016b081fc0f41bb4"),
    pricing = PricingSummary(Map(GBP -> Price(-3.75f, GBP))),
    zBillingPeriod = None,
    endDateCondition = OneTime,
    upToPeriods = None,
    upToPeriodsType = None,
  )
  private val discountPlan = subsv2.RatePlan(
    id = RatePlanId("8ad093fb90a5fb8c0190bdfd67d062a7"),
    productRatePlanId = ProductRatePlanId("2c92c0f96b03800b016b081fc04f1ba2"),
    productName = "Discounts",
    lastChangeType = Some("Add"),
    features = List.empty,
    chargedThroughDate = None,
    ratePlanCharges = NonEmptyList(holCharge),
    start = 28 Jul 2024,
    end = 29 Jul 2024,
  )

  val allRatePlans = List(mainPlan, discountPlan)
}
