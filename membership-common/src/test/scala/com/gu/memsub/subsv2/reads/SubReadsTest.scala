package com.gu.memsub.subsv2.reads

import com.gu.i18n.Currency._
import com.gu.lib.DateDSL._
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId, RatePlanId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.ReaderType.Patron
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.SubJsonReads._
import com.gu.memsub.{subsv2, _}
import org.specs2.mutable.Specification
import scalaz.NonEmptyList
import utils.Resource

class SubReadsTest extends Specification {

  "Subscription JSON reads" should {

    "Discard discount rate plans when reading JSON" in {

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

    "parse Patron reader type correctly from subscription" in {
      val json = Resource.getJson("rest/PatronReaderType.json")
      val subscription =
        SubJsonReads.subscriptionReads.reads(json).get

      subscription.readerType mustEqual Patron
    }
  }
}
