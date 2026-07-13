package com.gu.zuora.rest

import com.gu.i18n.Currency.GBP
import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.rest.ZuoraQueryReads._
import com.gu.zuora.soap.models.Queries
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class ZuoraQueryReadsTest extends Specification {

  "accountReads" should {
    "parse an object/account response, resolving currency and payment gateway" in {
      val json = Json.parse("""{
        "Id": "acc-1", "BillToId": "bill-1", "SoldToId": "sold-1", "BillCycleDay": 18,
        "CreditBalance": 0, "Currency": "GBP", "DefaultPaymentMethodId": "pm-1",
        "sfContactId__c": "003xxx", "PaymentGateway": "Stripe PaymentIntents GNM Membership"
      }""")
      val account = json.as[Queries.Account]
      account.billCycleDay must_== 18
      account.creditBalance must_== 0f
      account.currency must_== Some(GBP)
      account.defaultPaymentMethodId must beSome("pm-1")
      account.sfContactId must beSome("003xxx")
      account.paymentGateway must beSome
    }
  }

  "paymentMethodReads" should {
    "parse credit card expiry returned as JSON numbers into strings, and keep only the last 4 mask digits" in {
      val json = Json.parse("""{
        "Id": "pm-1", "Type": "CreditCardReferenceTransaction", "PaymentMethodStatus": "Active",
        "NumConsecutiveFailures": 0, "CreditCardMaskNumber": "************4242",
        "CreditCardExpirationMonth": 10, "CreditCardExpirationYear": 2026, "CreditCardType": "Visa"
      }""")
      val pm = json.as[Queries.PaymentMethod]
      pm.`type` must_== "CreditCardReferenceTransaction"
      pm.creditCardExpirationMonth must beSome("10")
      pm.creditCardExpirationYear must beSome("2026")
      pm.creditCardNumber must beSome("4242")
      pm.numConsecutiveFailures must beSome(0)
      pm.bankCode must beNone
    }
  }

  "invoiceItemReads" should {
    "sum charge and tax into the price" in {
      val json = Json.parse("""{
        "Id": "ii-1", "ChargeAmount": 10.3, "TaxAmount": 2, "ServiceStartDate": "2026-06-18",
        "ServiceEndDate": "2026-07-17", "ChargeNumber": "C-1", "ProductName": "Newspaper", "SubscriptionId": "sub-1"
      }""")
      val item = json.as[Queries.InvoiceItem]
      item.price must_== 12.3f
      item.serviceStartDate must_== new LocalDate("2026-06-18")
      item.subscriptionId must_== "sub-1"
    }
  }

  "queryResponseReads" should {
    "read account ids straight into AccountId" in {
      val json = Json.parse("""{"records": [{"Id": "acc-1"}, {"Id": "acc-2"}], "size": 2, "done": true}""")
      json.as[QueryResponse[AccountId]].records.map(_.get) must_== List("acc-1", "acc-2")
    }
  }
}
