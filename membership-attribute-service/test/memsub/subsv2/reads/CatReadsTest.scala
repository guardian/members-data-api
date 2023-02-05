package memsub.subsv2.reads

import com.gu.i18n.Currency._
import models.subscription.Benefit.Weekly
import models.subscription.Subscription.{ProductId, ProductRatePlanChargeId, ProductRatePlanId}
import models.subscription.subsv2.FrontendId.{OneYear, ThreeMonths}
import models.subscription.subsv2.reads.CatJsonReads._
import models.subscription.subsv2.{BillingPeriods, CatalogZuoraPlan, FixedPeriod, ZQuarter, ZYear, ZuoraCharge}
import models.subscription.{Price, PricingSummary, Status}
import org.specs2.mutable.Specification
import util.Resource

class CatReadsTest extends Specification {

  val plans = Resource.getJson("rest/Catalog.json").validate[List[CatalogZuoraPlan]].get

  "Catalog JSON reads" should {
    "Read a correctly formatted common zuora plan from the catalog" in {
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
          status = Status.current,
          frontendId = Some(OneYear),
        ),
      )

      result mustEqual expected

    }

    "Catalog JSON reads" should {
      "Read the GW three months plan from the catalog" in {
        val result = plans.find(_.id.get == "2c92c0f96df75b5a016df81ba1c62609")
        val expected = Some(
          CatalogZuoraPlan(
            id = ProductRatePlanId("2c92c0f96df75b5a016df81ba1c62609"),
            name = "GW Oct 18 - 3 Month - ROW",
            description = "",
            productId = ProductId("2c92c0f965f2121e01660fb1f1057b1a"),
            saving = None,
            charges = List(
              ZuoraCharge.apply(
                productRatePlanChargeId = ProductRatePlanChargeId("2c92c0f96df75b5a016df81ba1e9260b"),
                pricing = PricingSummary(
                  Map(
                    GBP -> Price(60f, GBP),
                    USD -> Price(81.3f, USD),
                    CAD -> Price(86.25f, CAD),
                    AUD -> Price(106.0f, AUD),
                    NZD -> Price(132.5f, NZD),
                    EUR -> Price(67.5f, EUR),
                  ),
                ),
                billingPeriod = Some(ZQuarter),
                specificBillingPeriod = None,
                model = "FlatFee",
                name = "GW Oct 18 - 3 Month - ROW",
                `type` = "Recurring",
                endDateCondition = FixedPeriod,
                upToPeriods = Some(1),
                upToPeriodsType = Some(BillingPeriods),
              ),
            ),
            benefits = Map(ProductRatePlanChargeId("2c92c0f96df75b5a016df81ba1e9260b") -> Weekly),
            status = Status.current,
            frontendId = Some(ThreeMonths),
          ),
        )

        result mustEqual expected
      }
    }
  }
}
