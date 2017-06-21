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
        attrs.copy(Tier = None, ContributionPaymentPlan = Some("Monthly Contributor")).isPaidTier shouldEqual false
      }
    }

    "isContributor returns" should {
      "true if the user is a contributor" in {
        attrs.copy(ContributionPaymentPlan = Some("Monthly Contribution")).isContributor shouldEqual true
      }

      "true if the user is a contributor and a Member" in {
        attrs.copy(Tier = Some("Friend"), ContributionPaymentPlan = Some("Monthly Contribution")).isContributor shouldEqual true
      }

      "false if the user is not a Contributor but a member" in {
        attrs.copy(Tier = Some("Friend"), ContributionPaymentPlan = None).isContributor shouldEqual false
      }
    }

    "maybeCardHasExpired returns" should {
      "true if the card expiry is in the past" in {
        attrs.copy(CardExpirationMonth = Some(1), CardExpirationYear = Some(2017)).maybeCardHasExpired shouldEqual Some(true)
      }
      "false if the card expiry is in the past" in {
        attrs.copy(CardExpirationMonth = Some(1), CardExpirationYear = Some(3000)).maybeCardHasExpired shouldEqual Some(false)
      }
    }
  }
}
