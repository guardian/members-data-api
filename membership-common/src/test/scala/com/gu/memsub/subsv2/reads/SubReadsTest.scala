package com.gu.memsub.subsv2.reads

import com.gu.i18n.Currency._
import com.gu.lib.DateDSL._
import com.gu.memsub.Benefit.Weekly
import com.gu.memsub.BillingPeriod.Quarter
import com.gu.memsub.Product.WeeklyDomestic
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId, RatePlanId, SubscriptionRatePlanChargeId}
import com.gu.memsub._
import com.gu.memsub.subsv2.ReaderType.Patron
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.SubJsonReads._
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import utils.Resource
import scalaz.NonEmptyList

class SubReadsTest extends Specification {

  "Subscription JSON reads" should {

    "Discard discount rate plans when reading JSON" in {

      val plans = Resource.getJson("rest/plans/Promo.json").validate[List[SubscriptionZuoraPlan]](subZuoraPlanListReads).get
      plans mustEqual List(
        SubscriptionZuoraPlan(
          id = RatePlanId("2c92c0f957220b5d01573252b3bb7c71"),
          productRatePlanId = ProductRatePlanId("2c92c0f94f2acf73014f2c908f671591"),
          productName = "Digital Pack",
          lastChangeType = None,
          features = List.empty,
          chargedThroughDate = None,
          charges = NonEmptyList(
            ZuoraCharge(
              id = SubscriptionRatePlanChargeId("2c92c0f957220b5d01573252b3c67c72"),
              productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f94f2acf73014f2c91940a166d"),
              pricing = PricingSummary(
                Map(
                  GBP -> Price(11.99f, GBP),
                ),
              ),
              billingPeriod = Some(ZMonth),
              model = "FlatFee",
              name = "Digital Pack Monthly",
              `type` = "Recurring",
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

    "parse Patron reader type correctly from subscription" in {
      val now = LocalDate.parse("2020-10-19")
      val json = Resource.getJson("rest/PatronReaderType.json")
      val plan = RatePlan(
        id = RatePlanId("rpid"),
        productRatePlanId = ProductRatePlanId("prpid"),
        name = "n",
        description = "d",
        productName = "pn",
        lastChangeType = None,
        productType = "pt",
        product = WeeklyDomestic,
        features = Nil,
        charges = RatePlanCharge(
          benefit = Weekly,
          billingPeriod = Quarter,
          price = PricingSummary(Map.empty),
          chargeId = ProductRatePlanChargeId("prpcid"),
          subRatePlanChargeId = SubscriptionRatePlanChargeId("rpcid"),
        ),
        chargedThrough = None,
        start = now,
        end = now,
      )
      val subscription =
        SubJsonReads.subscriptionReads.reads(json).get(NonEmptyList(plan))

      subscription.readerType mustEqual Patron
    }
  }
}
