package services.zuora.rest

import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.zuora.rest.ZuoraRestService.{PaymentMethodDetails, PaymentMethodResponse}

class PaymentMethodResponseReadsTest extends Specification {

  "PaymentMethodResponse reads" should {
    "parse a credit card that has never been charged (no LastTransactionDateTime or NumConsecutiveFailures), reading the expiry as numbers and keeping only the last 4 mask digits" in {
      val json = Json.parse("""{
        "Type": "CreditCardReferenceTransaction", "PaymentMethodStatus": "Active",
        "CreditCardMaskNumber": "************4242",
        "CreditCardExpirationMonth": 10, "CreditCardExpirationYear": 2026, "CreditCardType": "Visa"
      }""")
      val pm = json.as[PaymentMethodResponse]
      pm.paymentMethodType must_== "CreditCardReferenceTransaction"
      pm.lastTransactionDateTime must beNone
      pm.numConsecutiveFailures must beNone
      pm.details must beLike { case card: PaymentMethodDetails.Card =>
        (card.expirationMonth must beSome(10)) and
          (card.expirationYear must beSome(2026)) and
          (card.number must beSome("4242")) and
          (card.isReferenceTransaction must beTrue)
      }
    }

    "parse a direct debit bank transfer" in {
      val json = Json.parse("""{
        "Type": "BankTransfer", "NumConsecutiveFailures": 0,
        "LastTransactionDateTime": "2026-06-18T00:00:00.000+01:00",
        "MandateID": "mandate-1", "BankTransferAccountName": "Frank Poole",
        "BankTransferAccountNumberMask": "****4444", "BankCode": "200000"
      }""")
      val pm = json.as[PaymentMethodResponse]
      pm.paymentMethodType must_== "BankTransfer"
      pm.details must beLike { case bankTransfer: PaymentMethodDetails.BankTransfer =>
        (bankTransfer.mandateId must beSome("mandate-1")) and
          (bankTransfer.accountName must beSome("Frank Poole")) and
          (bankTransfer.bankCode must beSome("200000"))
      }
    }

    "parse a PayPal payment method, keeping the email as a required field rather than an Option" in {
      val json = Json.parse("""{ "Type": "PayPal", "PaypalEmail": "frank.poole@example.com" }""")
      json.as[PaymentMethodResponse].details must_== PaymentMethodDetails.PayPal("frank.poole@example.com")
    }

    "return a JsError for a PayPal payment method with no email, rather than accepting it" in {
      val json = Json.parse("""{ "Type": "PayPal" }""")
      json.validate[PaymentMethodResponse].isError must beTrue
    }

    "keep an unknown payment method type as Other rather than failing to parse the whole payload" in {
      val json = Json.parse("""{ "Type": "AmazonPay" }""")
      json.as[PaymentMethodResponse].details must_== PaymentMethodDetails.Other("AmazonPay")
    }

    "return a JsError, rather than throwing, when LastTransactionDateTime is malformed" in {
      val json = Json.parse("""{
        "Type": "CreditCardReferenceTransaction", "PaymentMethodStatus": "Active",
        "LastTransactionDateTime": "not-a-real-date",
        "CreditCardMaskNumber": "************4242",
        "CreditCardExpirationMonth": 10, "CreditCardExpirationYear": 2026, "CreditCardType": "Visa"
      }""")
      json.validate[PaymentMethodResponse].isError must beTrue
    }
  }
}
