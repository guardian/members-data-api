package models

import org.specs2.mutable.Specification

class AttributesTest extends Specification {

  "AttributesTest" should {
    val attrs = Attributes("123", "tier", None)

    "isPaidTier returns" should {
      "true if the user is not a Guardian Friend" in {
        attrs.copy(Tier = "Paid tier").isPaidTier shouldEqual true
      }

      "false if the user is a Guardian Friend" in {
        attrs.copy(Tier = "Friend").isPaidTier shouldEqual false
      }
    }
  }
}
