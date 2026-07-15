package com.gu.zuora.rest

import com.gu.i18n.Country
import com.gu.memsub.Subscription.AccountId
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

  "CreatePaymentMethodObject writes for a bank transfer" should {
    "map to the Zuora Direct Debit zObject fields" in {
      val json =
        Json.toJson(CreatePaymentMethodObject(AccountId("acc-1"), Commands.BankTransfer("Jane Doe", "55779911", "200000", "Jane", "Doe", "GB")))
      (json \ "AccountId").as[String] must_== "acc-1"
      (json \ "Type").as[String] must_== "BankTransfer"
      (json \ "BankTransferType").as[String] must_== "DirectDebitUK"
      (json \ "BankTransferAccountName").as[String] must_== "Jane Doe"
      (json \ "BankTransferAccountNumber").as[String] must_== "55779911"
      (json \ "BankCode").as[String] must_== "200000"
    }
  }

  "CreatePaymentMethodObject writes for a credit card reference" should {
    "map the card reference fields and normalise a common card type" in {
      val json = Json.toJson(
        CreatePaymentMethodObject(
          AccountId("acc-1"),
          Commands.CreditCardReferenceTransaction("pm_token", "cus_token", "4242", Some(Country.UK), 12, 2030, "visa"),
        ),
      )
      (json \ "AccountId").as[String] must_== "acc-1"
      (json \ "Type").as[String] must_== "CreditCardReferenceTransaction"
      (json \ "TokenId").as[String] must_== "pm_token"
      (json \ "SecondTokenId").as[String] must_== "cus_token"
      (json \ "CreditCardNumber").as[String] must_== "4242"
      (json \ "CreditCardCountry").as[String] must_== "GB"
      (json \ "CreditCardType").as[String] must_== "Visa"
    }
    "pass an unrecognised card type through with spaces removed, and omit the country when absent" in {
      val json = Json.toJson(
        CreatePaymentMethodObject(AccountId("acc-1"), Commands.CreditCardReferenceTransaction("t", "c", "4242", None, 1, 2030, "Some Scheme")),
      )
      (json \ "CreditCardType").as[String] must_== "SomeScheme"
      (json \ "CreditCardCountry").toOption must beNone
    }
  }

  "ObjectCreateResponse reads" should {
    "read the PascalCase Id and Success fields" in {
      val response = Json.parse("""{"Id":"pm-1","Success":true}""").as[ObjectCreateResponse]
      response.id must beSome("pm-1")
      response.success must beTrue
    }
    "treat a missing Id on a failure response as absent rather than failing to parse" in {
      val response = Json.parse("""{"Success":false}""").as[ObjectCreateResponse]
      response.id must beNone
      response.success must beFalse
    }
  }
}
