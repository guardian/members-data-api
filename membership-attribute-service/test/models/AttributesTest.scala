package models

import org.specs2.mutable.Specification

class AttributesTest extends Specification {

  "MembershipAttributesTest" should {
    val attrs = Attributes("123", "tier", None)

    "isPaidTier returns" should {
      "true if the user is not a Guardian Friend" in {
        attrs.copy(tier = "Paid tier").isPaidTier shouldEqual true
      }

      "false if the user is a Guardian Friend" in {
        attrs.copy(tier = "Friend").isPaidTier shouldEqual false
      }
    }
  }
}
