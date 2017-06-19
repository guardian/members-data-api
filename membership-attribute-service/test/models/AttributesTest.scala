package models

import com.gu.memsub.Benefit.Contributor
import org.specs2.mutable.Specification

class AttributesTest extends Specification {

  "AttributesTest" should {
    val attrs = Attributes(UserId = "123", Tier = None, MembershipNumber = None, ContributionFrequency = None, MembershipJoinDate = None)

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
        attrs.copy(Tier = None, ContributionFrequency = Some("Monthly Contributor")).isPaidTier shouldEqual false
      }
    }

    "isContributor returns" should {
      "true if the user is a contributor" in {
        attrs.copy(ContributionFrequency = Some("Monthly Contribution")).isContributor shouldEqual true
      }

      "true if the user is a contributor and a Member" in {
        attrs.copy(Tier = Some("Friend"), ContributionFrequency = Some("Monthly Contribution")).isContributor shouldEqual true
      }

      "false if the user is not a Contributor but a member" in {
        attrs.copy(Tier = Some("Friend"), ContributionFrequency = None).isContributor shouldEqual false
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
