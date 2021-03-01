package services

import models.SupporterRatePlanItem
import org.joda.time.{DateTime, LocalDate}
import org.specs2.mutable.Specification


class SupporterRatePlanToAttributesMapperTest extends Specification {
  "Mapper" should {
    "identify a monthly contribution" in {
      val mapper = new SupporterRatePlanToAttributesMapper("PROD")
      val termEndDate = LocalDate.now().plusDays(5)

      val attributes = mapper.attributesFromSupporterRatePlans(
        "9999",
        List(SupporterRatePlanItem(
          "9999",
          None,
          "some-rate-plan-id",
          "2c92a0fb4edd70c8014edeaa4eae220a",
          "Digital Subscription Monthly",
          termEndDate
        ))
      )
      attributes.digitalSubscriberHasActivePlan must_=== true
      attributes.latestDigitalSubscriptionExpiryDate must beSome(termEndDate)
    }
  }
}
