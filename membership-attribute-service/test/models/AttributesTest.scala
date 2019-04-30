package models

import org.specs2.mutable.Specification

class AttributesTest extends Specification {

  "AttributesTest" should {
    val attrs = Attributes(UserId = "123")

    "isPaidTier returns" should {
      "true if the user is not a Guardian Friend" in {
        attrs.copy(Tier = Some("Supporter")).isPaidTier shouldEqual true
        attrs.copy(Tier = Some("Partner")).isPaidTier shouldEqual true
        attrs.copy(Tier = Some("Patron")).isPaidTier shouldEqual true
      }

      "false if the user is a Guardian Friend" in {
        attrs.copy(Tier = Some("Friend")).isPaidTier shouldEqual false
      }

      "false if the user is a Contributor but not a member" in {
        attrs.copy(Tier = None, RecurringContributionPaymentPlan = Some("Monthly Contributor")).isPaidTier shouldEqual false
      }
    }

    "isContributor returns" should {
      "true if the user is a contributor" in {
        attrs.copy(RecurringContributionPaymentPlan = Some("Monthly Contribution")).isIsRecurringContributor shouldEqual true
      }

      "true if the user is a contributor and a Member" in {
        attrs.copy(Tier = Some("Friend"), RecurringContributionPaymentPlan = Some("Monthly Contribution")).isIsRecurringContributor shouldEqual true
      }

      "false if the user is not a Contributor but a member" in {
        attrs.copy(Tier = Some("Friend"), RecurringContributionPaymentPlan = None).isIsRecurringContributor shouldEqual false
      }
    }
  }
}
