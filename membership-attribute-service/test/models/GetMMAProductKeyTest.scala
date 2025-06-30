package models

import acceptance.data.TestSingleCharge
import com.gu.memsub.Subscription.RatePlanId
import com.gu.memsub.subsv2.RatePlan
import com.gu.memsub.subsv2.services.TestCatalog
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalaz.NonEmptyList

class GetMMAProductKeyTest extends AnyFlatSpec with Matchers {

  "getMMAProductKey" should "handle a newspaper subscription" in {
    val testRatePlanNewspaper = RatePlan(
      RatePlanId("idid"),
      TestCatalog.homeDeliveryPrpId,
      "Home Delivery",
      None,
      Nil,
      NonEmptyList(
        TestSingleCharge(chargeId = TestCatalog.ProductRatePlanChargeIds.homeDelMondayCharge),
      ),
    )
    val actual = AccountDetails.getMMAProductKey(TestCatalog.catalog, testRatePlanNewspaper)
    actual should be("Home Delivery")
  }

  it should "handle a plus subscription" in {
    val testRatePlanNewspaper = RatePlan(
      RatePlanId("idid"),
      TestCatalog.homeDeliveryPlusPrpId,
      "Home Delivery",
      None,
      Nil,
      NonEmptyList(
        TestSingleCharge(chargeId = TestCatalog.ProductRatePlanChargeIds.homeDeliveryPlusChargeId),
        TestSingleCharge(chargeId = TestCatalog.ProductRatePlanChargeIds.homeDelMondayCharge),
      ),
    )
    val actual = AccountDetails.getMMAProductKey(TestCatalog.catalog, testRatePlanNewspaper)
    actual should be("Home Delivery + Digital")
  }

}
