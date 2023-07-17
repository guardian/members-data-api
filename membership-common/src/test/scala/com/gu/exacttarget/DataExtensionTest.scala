package com.gu.exacttarget

import org.specs2.mutable.Specification

class DataExtensionTest extends Specification {

  "WelcomeEmailDataExtension" should {
    "build correct trigger key for welcome e-mail" in {
      case object TestWelcomeEmail extends WelcomeEmailDataExtension {
        val name = "test-product"
      }

      TestWelcomeEmail.getTSKey mustEqual "triggered-send-keys.test-product.welcome1"
    }
  }
}
