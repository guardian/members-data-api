package services

import com.gu.zuora.ZuoraRestService.{RestRatePlan, RestSubscription, RestSubscriptions}
import models.Attributes
import org.joda.time.LocalDate
import org.specs2.mutable.Specification

class AttributesMakerTest extends Specification {

  "attributes" should {
    val testId = "123"
    val member = RestSubscriptions(
      subscriptions = List(RestSubscription(
        ratePlans = List(RestRatePlan("Supporter","Non Founder Supporter - annual")),
        contractEffectiveDate = "2017-06-29")
      )
    )

    val contributor = RestSubscriptions(
      subscriptions = List(RestSubscription(
        ratePlans = List(RestRatePlan("Contributor","Monthly Contribution")),
        contractEffectiveDate = "2017-06-30")
      )
    )

//    "return none when no subs" in {
//        //needed??
//    }


    "return none when only sub is digipack" in { //for now!
      val digipack = RestSubscriptions(
        subscriptions = List(RestSubscription(
          ratePlans = List(RestRatePlan("Digital Pack","Digital Pack Monthly")),
          contractEffectiveDate = "2017-07-04")
        )
      )

      AttributesMaker.attributes(testId, List(digipack)) === None
    }

    "return attributes when one sub is a membership" in {
      val expected = Some(Attributes(
          UserId = testId,
          Tier = Some("Supporter"),
          MembershipNumber = None,
          AdFree = None,
          Wallet = None,
          RecurringContributionPaymentPlan = None,
          MembershipJoinDate = Some(new LocalDate("2017-06-29"))
        )
      )
      AttributesMaker.attributes(testId, List(member)) === expected

    }

    "return attributes when one sub is a recurring contribution" in {

      val expected = Some(Attributes(
          UserId = testId,
          Tier = None,
          MembershipNumber = None,
          AdFree = None,
          Wallet = None,
          RecurringContributionPaymentPlan = Some("Monthly Contribution"),
          MembershipJoinDate = None
        )
      )
      AttributesMaker.attributes(testId, List(contributor)) === expected
    }

    "return attributes relevant to both when one sub is a contribution and the other a membership" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Supporter"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(new LocalDate("2017-06-29"))
      )
      )
      AttributesMaker.attributes(testId, List(contributor, member)) === expected
    }
  }
}

