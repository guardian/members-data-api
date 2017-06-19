package models

import org.specs2.mutable.Specification

class AttributesTest extends Specification {

  "AttributesTest" should {
    val attrs = Attributes(UserId = "123", Tier = Some("tier"), MembershipNumber = None)

    "isPaidTier returns" should {
      "true if the user is not a Guardian Friend" in {
        attrs.copy(Tier = Some("Supporter")).isPaidTier shouldEqual true
        attrs.copy(Tier = Some("Partner")).isPaidTier shouldEqual true
        attrs.copy(Tier = Some("Patron")).isPaidTier shouldEqual true
      }

      "false if the user is a Guardian Friend" in {
        attrs.copy(Tier = Some("Friend")).isPaidTier shouldEqual false
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
