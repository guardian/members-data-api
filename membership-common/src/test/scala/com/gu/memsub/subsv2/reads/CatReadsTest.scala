package com.gu.memsub.subsv2.reads
import com.gu.Diff
import com.gu.i18n.Currency._
import com.gu.memsub.Benefit.{SupporterPlus, Weekly}
import com.gu.memsub.Subscription.{ProductId, ProductRatePlanChargeId, ProductRatePlanId, SubscriptionRatePlanChargeId}
import com.gu.memsub._
import com.gu.memsub.subsv2.FrontendId.{OneYear, ThreeMonths}
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.CatJsonReads._
import com.softwaremill.diffx.generic.auto.diffForCaseClass
import org.scalatest.flatspec.AnyFlatSpec
import utils.Resource

class CatReadsTest extends AnyFlatSpec {

  val plans = Resource.getJson("rest/Catalog.json").validate[List[CatalogZuoraPlan]].get

  "Catalog JSON reads" should "Read a correctly formatted common zuora plan from the catalog" in {
    val result = plans.find(_.id.get == "2c92c0f958aa455e0158aa6bc72f2aba")
    val expected = Some(
      CatalogZuoraPlan(
        id = ProductRatePlanId("2c92c0f958aa455e0158aa6bc72f2aba"),
        name = "Guardian Weekly 1 Year",
        description = "",
        productId = ProductId("2c92c0f8574b2b8101574c4a9473068b"),
        saving = None,
        charges = List(
          ZuoraCharge.apply(
            productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f958aa45600158ac00e19d5daf"),
            pricing = PricingSummary(
              Map(
                GBP -> Price(120f, GBP),
                USD -> Price(240f, USD),
              ),
            ),
            billingPeriod = Some(ZYear),
            specificBillingPeriod = None,
            model = "FlatFee",
            name = "Zone A 1 Year",
            `type` = "Recurring",
            endDateCondition = FixedPeriod,
            upToPeriods = Some(1),
            upToPeriodsType = Some(BillingPeriods),
          ),
        ),
        benefits = Map(ProductRatePlanChargeId("2c92c0f958aa45600158ac00e19d5daf") -> Weekly),
        status = Status.Current,
        frontendId = Some(OneYear),
        productTypeOption = Some("Guardian Weekly"),
      ),
    )

    Diff.assertEquals(expected, result)

  }

  it should "Read the GW three months plan from the catalog" in {
    val result = plans.find(_.id.get == "2c92c0f96df75b5a016df81ba1c62609")
    val expected = Some(
      CatalogZuoraPlan(
        id = ProductRatePlanId("2c92c0f96df75b5a016df81ba1c62609"),
        name = "GW GIFT Oct 18 - 3 Month - ROW",
        description = "",
        productId = ProductId("2c92c0f965f2121e01660fb1f1057b1a"),
        saving = None,
        charges = List(
          ZuoraCharge.apply(
            productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f96df75b5a016df81ba1e9260b"),
            pricing = PricingSummary(
              Map(
                GBP -> Price(74.4f, GBP),
                USD -> Price(99f, USD),
              ),
            ),
            billingPeriod = Some(ZQuarter),
            specificBillingPeriod = None,
            model = "FlatFee",
            name = "GW GIFT Oct 18 - 3 Month - ROW",
            `type` = "Recurring",
            endDateCondition = FixedPeriod,
            upToPeriods = Some(1),
            upToPeriodsType = Some(BillingPeriods),
          ),
        ),
        benefits = Map(ProductRatePlanChargeId("2c92c0f96df75b5a016df81ba1e9260b") -> Weekly),
        status = Status.Current,
        frontendId = Some(ThreeMonths),
        productTypeOption = Some("Guardian Weekly"),
      ),
    )

    Diff.assertEquals(expected, result)

  }

  it should "Read the Tier Three Domestic Monthly plan from the catalog" in {
    val result = plans.find(_.id.get == "8ad097b48ff26452019001cebac92376")
    val expected = Some(
      CatalogZuoraPlan(
        id = ProductRatePlanId("8ad097b48ff26452019001cebac92376"),
        name = "Supporter Plus & Guardian Weekly Domestic - Monthly",
        description = "",
        productId = ProductId("8ad097b48ff26452019001c67ad32035"),
        saving = None,
        charges = List(
          ZuoraCharge.apply(
            productRatePlanChargeId = ProductRatePlanChargeId("8ad097b48ff26452019001d46f8824e2"),
            pricing = PricingSummary(
              Map(
                GBP -> Price(15.0f, GBP),
              ),
            ),
            billingPeriod = Some(ZMonth),
            specificBillingPeriod = None,
            model = "FlatFee",
            name = "Guardian Weekly",
            `type` = "Recurring",
            endDateCondition = SubscriptionEnd,
            upToPeriods = None,
            upToPeriodsType = None,
          ),
          ZuoraCharge.apply(
            ProductRatePlanChargeId("8ad097b48ff26452019001d78ee325d1"),
            PricingSummary(Map(GBP -> Price(10.0f, GBP))),
            Some(ZMonth),
            None,
            "FlatFee",
            "Supporter Plus",
            "Recurring",
            SubscriptionEnd,
            None,
            None,
          ),
        ),
        benefits = Map(
          ProductRatePlanChargeId("8ad097b48ff26452019001d46f8824e2") -> Weekly,
          ProductRatePlanChargeId("8ad097b48ff26452019001d78ee325d1") -> SupporterPlus,
        ),
        status = Status.Current,
        frontendId = None,
        productTypeOption = Some("Tier Three"),
      ),
    )

    Diff.assertEquals(expected, result)

  }

}
