package services

import com.github.nscala_time.time.Implicits._
import models.Attributes
import org.specs2.mutable.Specification

import testdata.SubscriptionTestDataHelper._

class AttributesMakerTest extends Specification {

  "attributes" should {
    val testId = "123"

    "return attributes when digipack sub" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = None,
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      AttributesMaker.attributes(testId, List(digipack), referenceDate) === expected
    }

    "return attributes when one of the subs has a digital benefit" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = None,
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      AttributesMaker.attributes(testId, List(sunday, sundayPlus), referenceDate) === expected
    }

    "return none when only sub is expired" in {
      AttributesMaker.attributes(testId, List(expiredMembership), referenceDate) === None
    }

    "return attributes when there is one membership sub" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Supporter"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.attributes(testId, List(membership), referenceDate) === expected
    }

    "return attributes when one sub is expired and one is not" in {
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

      AttributesMaker.attributes(testId, List(expiredMembership, contributor), referenceDate) === expected
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
      AttributesMaker.attributes(testId, List(contributor), referenceDate) === expected
    }

    "return attributes relevant to both when one sub is a contribution and the other a membership" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Supporter"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.attributes(testId, List(contributor, membership), referenceDate) === expected
    }

    "return attributes when the membership is a friend tier" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Friend"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.attributes(testId, List(friend), referenceDate) === expected
    }
  }
}

