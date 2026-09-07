package com.gu.zuora.api

import org.specs2.mutable.Specification

class PaymentGatewayTest extends Specification {

  "PaymentGateway.getByName" should {
    "resolve newly supported payment gateway names" in {
      PaymentGateway.getByName("PayPal Complete Payments") must_== Some(PayPalCompletePaymentsGateway)
      PaymentGateway.getByName("Stripe Bank Transfer - GNM Membership") must_== Some(StripeBankTransferMembershipGateway)
      PaymentGateway.getByName("PayPal - Observer - Tortoise Media") must_== Some(PayPalTortoiseMediaGateway)
    }

    "return none for an unknown payment gateway" in {
      PaymentGateway.getByName("Unknown gateway") must beNone
    }
  }
}
