package com.gu.memsub.subsv2.reads
import com.gu.Diff
import com.gu.i18n.Currency._
import com.gu.memsub.ProductRatePlanChargeProductType.{SupporterPlus, Weekly}
import com.gu.memsub.Subscription.{ProductId, ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub._
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.CatJsonReads._
import com.softwaremill.diffx.generic.auto.diffForCaseClass
import org.scalatest.flatspec.AnyFlatSpec
import utils.Resource

class CatReadsTest extends AnyFlatSpec {

  val plans = Resource.getJson("rest/Catalog.json").validate[List[ProductRatePlan]](productsReads).get

  "Catalog JSON reads" should "Read a correctly formatted common zuora plan from the catalog" in {
    val result = plans.find(_.id.get == "2c92c0f958aa455e0158aa6bc72f2aba")
    val expected = Some(
      ProductRatePlan(
        id = ProductRatePlanId("2c92c0f958aa455e0158aa6bc72f2aba"),
        name = "Guardian Weekly 1 Year",
        productId = ProductId("2c92c0f8574b2b8101574c4a9473068b"),
        productRatePlanCharges = Map(ProductRatePlanChargeId("2c92c0f958aa45600158ac00e19d5daf") -> Weekly),
        productTypeOption = Some(ProductType("Guardian Weekly")),
      ),
    )

    Diff.assertEquals(expected, result)

  }

  it should "Read the GW three months plan from the catalog" in {
    val result = plans.find(_.id.get == "2c92c0f96df75b5a016df81ba1c62609")
    val expected = Some(
      ProductRatePlan(
        id = ProductRatePlanId("2c92c0f96df75b5a016df81ba1c62609"),
        name = "GW GIFT Oct 18 - 3 Month - ROW",
        productId = ProductId("2c92c0f965f2121e01660fb1f1057b1a"),
        productRatePlanCharges = Map(ProductRatePlanChargeId("2c92c0f96df75b5a016df81ba1e9260b") -> Weekly),
        productTypeOption = Some(ProductType("Guardian Weekly")),
      ),
    )

    Diff.assertEquals(expected, result)

  }

  it should "Read the Tier Three Domestic Monthly plan from the catalog" in {
    val result = plans.find(_.id.get == "8ad097b48ff26452019001cebac92376")
    val expected = Some(
      ProductRatePlan(
        id = ProductRatePlanId("8ad097b48ff26452019001cebac92376"),
        name = "Supporter Plus & Guardian Weekly Domestic - Monthly",
        productId = ProductId("8ad097b48ff26452019001c67ad32035"),
        productRatePlanCharges = Map(
          ProductRatePlanChargeId("8ad097b48ff26452019001d46f8824e2") -> Weekly,
          ProductRatePlanChargeId("8ad097b48ff26452019001d78ee325d1") -> SupporterPlus,
        ),
        productTypeOption = Some(ProductType("Tier Three")),
      ),
    )

    Diff.assertEquals(expected, result)

  }

}
