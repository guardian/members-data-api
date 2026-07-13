package com.gu.zuora.rest

import com.gu.zuora.rest.ZuoraPaymentWrites._
import com.gu.zuora.soap.models.Commands
import org.specs2.mutable.Specification
import play.api.libs.json.{JsNull, Json}

class ZuoraPaymentWritesTest extends Specification {

  "AccountPaymentUpdate writes" should {
    "send an explicit null to clear the default payment method" in {
      val json = Json.toJson(AccountPaymentUpdate(None, "GoCardless", autoPay = false))
      (json \ "defaultPaymentMethodId").get must_== JsNull
      (json \ "paymentGateway").as[String] must_== "GoCardless"
      (json \ "autoPay").as[Boolean] must_== false
    }
    "send the id to set the default payment method" in {
      val json = Json.toJson(AccountPaymentUpdate(Some("pm-1"), "Stripe", autoPay = true))
      (json \ "defaultPaymentMethodId").as[String] must_== "pm-1"
      (json \ "autoPay").as[Boolean] must_== true
    }
  }

  "paymentMethod object for a bank transfer" should {
    "map to the Zuora Direct Debit zObject fields" in {
      val json = paymentMethod("acc-1", Commands.BankTransfer("Jane Doe", "55779911", "200000", "Jane", "Doe", "GB"))
      (json \ "Type").as[String] must_== "BankTransfer"
      (json \ "BankTransferType").as[String] must_== "DirectDebitUK"
      (json \ "BankTransferAccountName").as[String] must_== "Jane Doe"
      (json \ "BankTransferAccountNumber").as[String] must_== "55779911"
      (json \ "BankCode").as[String] must_== "200000"
      (json \ "AccountId").as[String] must_== "acc-1"
    }
  }

  "creditCardReference object" should {
    "map the card reference fields and normalise the card type" in {
      val json = creditCardReference("acc-1", "pm_token", "cus_token", "4242", Some("GB"), 12, 2030, "visa")
      (json \ "Type").as[String] must_== "CreditCardReferenceTransaction"
      (json \ "TokenId").as[String] must_== "pm_token"
      (json \ "SecondTokenId").as[String] must_== "cus_token"
      (json \ "CreditCardNumber").as[String] must_== "4242"
      (json \ "CreditCardCountry").as[String] must_== "GB"
      (json \ "CreditCardType").as[String] must_== "Visa"
    }
    "omit the card type and country when absent or unrecognised" in {
      val json = creditCardReference("acc-1", "t", "c", "4242", None, 1, 2030, "some-unknown-scheme")
      (json \ "CreditCardType").toOption must beNone
      (json \ "CreditCardCountry").toOption must beNone
    }
  }
}
