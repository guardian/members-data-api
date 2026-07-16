package services.zuora.rest

import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.zuora.rest.ZuoraRestService.PaymentMethodResponse

class PaymentMethodResponseReadsTest extends Specification {

  "PaymentMethodResponse reads" should {
    "parse a credit card, reading the expiry as numbers and keeping only the last 4 mask digits" in {
      val json = Json.parse("""{
        "Type": "CreditCardReferenceTransaction", "PaymentMethodStatus": "Active",
        "NumConsecutiveFailures": 0, "LastTransactionDateTime": "2026-06-18T00:00:00.000+01:00",
        "CreditCardMaskNumber": "************4242",
        "CreditCardExpirationMonth": 10, "CreditCardExpirationYear": 2026, "CreditCardType": "Visa"
      }""")
      val pm = json.as[PaymentMethodResponse]
      pm.paymentMethodType must_== "CreditCardReferenceTransaction"
      pm.creditCardExpirationMonth must beSome(10)
      pm.creditCardExpirationYear must beSome(2026)
      pm.creditCardNumber must beSome("4242")
      pm.numConsecutiveFailures must_== 0
      pm.bankCode must beNone
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
      pm.mandateId must beSome("mandate-1")
      pm.bankTransferAccountName must beSome("Frank Poole")
      pm.bankCode must beSome("200000")
    }
  }
}
