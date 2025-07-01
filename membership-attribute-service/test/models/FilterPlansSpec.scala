package models

import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.FixedDiscountRecurringTestData
import com.gu.memsub.subsv2.reads.SubJsonReads.subscriptionReads
import com.gu.memsub.subsv2.services.TestCatalog
import com.gu.monitoring.SafeLogging
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import utils.Resource
import utils.TestLogPrefix.testLogPrefix

class FilterPlansSpec extends Specification with SafeLogging {

  "subscription response plan filtering" should {
    "handle a fixed discount" in {
      val actualSubscription = Resource.getJson("rest/plans/WithRecurringFixedDiscount.json").validate[Subscription](subscriptionReads).get

      val expected = FixedDiscountRecurringTestData.mainPlan

      val actual = new FilterPlans(actualSubscription, TestCatalog.catalogProd, LocalDate.parse("2099-01-01"))

      actual.currentPlans must containTheSameElementsAs(List(expected))
    }
  }

}
