package com.gu.zuora.rest

import com.gu.i18n.Currency
import com.gu.zuora.ZuoraLookup
import com.gu.zuora.api.PaymentGateway
import com.gu.zuora.soap.models.Queries
import org.joda.time.LocalDate
import play.api.libs.json._

/** JSON readers that let ZuoraSoapService's read methods hit Zuora REST (object/{type}/{id} and action/query) instead of SOAP query, mapping the
  * responses back to the existing SOAP query case classes so callers are unaffected. Field names and parsing mirror the old SOAP readers in
  * com.gu.zuora.soap.Readers. See https://developer.zuora.com/api-references/api/operation/Object_GETAccount
  */
object ZuoraQueryReads {

  case class RestQuery(queryString: String)
  implicit val restQueryWrites: Writes[RestQuery] = Json.writes[RestQuery]

  case class QueryResponse[A](records: List[A])
  implicit def queryResponseReads[A](implicit r: Reads[A]): Reads[QueryResponse[A]] =
    (__ \ "records").read[List[A]].map(QueryResponse(_))

  case class AccountIdRecord(id: String)
  implicit val accountIdRecordReads: Reads[AccountIdRecord] = (__ \ "Id").read[String].map(AccountIdRecord)

  // Zuora returns some fields (e.g. credit card expiry) as JSON numbers; the SOAP readers treated everything as strings, so normalise to String.
  private def optString(json: JsValue, field: String): JsResult[Option[String]] =
    (json \ field)
      .validateOpt[JsValue]
      .map(_.flatMap {
        case JsString(s) => Some(s)
        case JsNumber(n) => Some(n.toBigInt.toString)
        case _ => None
      })

  implicit val accountReads: Reads[Queries.Account] = Reads { json =>
    for {
      id <- (json \ "Id").validate[String]
      billToId <- (json \ "BillToId").validate[String]
      soldToId <- (json \ "SoldToId").validate[String]
      billCycleDay <- (json \ "BillCycleDay").validate[Int]
      creditBalance <- (json \ "CreditBalance").validate[Float]
      currency <- (json \ "Currency").validateOpt[String]
      defaultPaymentMethodId <- (json \ "DefaultPaymentMethodId").validateOpt[String]
      sfContactId <- (json \ "sfContactId__c").validateOpt[String]
      paymentGateway <- (json \ "PaymentGateway").validateOpt[String]
    } yield Queries.Account(
      id = id,
      billToId = billToId,
      soldToId = soldToId,
      billCycleDay = billCycleDay,
      creditBalance = creditBalance,
      currency = currency.flatMap(Currency.fromString),
      defaultPaymentMethodId = defaultPaymentMethodId,
      sfContactId = sfContactId,
      paymentGateway = paymentGateway.flatMap(PaymentGateway.getByName),
    )
  }

  implicit val contactReads: Reads[Queries.Contact] = Reads { json =>
    for {
      id <- (json \ "Id").validate[String]
      firstName <- (json \ "FirstName").validate[String]
      lastName <- (json \ "LastName").validate[String]
      postalCode <- (json \ "PostalCode").validateOpt[String]
      country <- (json \ "Country").validateOpt[String]
      email <- (json \ "WorkEmail").validateOpt[String]
    } yield Queries.Contact(
      id = id,
      firstName = firstName,
      lastName = lastName,
      postalCode = postalCode,
      country = country.flatMap(ZuoraLookup.country),
      email = email,
    )
  }

  implicit val paymentMethodReads: Reads[Queries.PaymentMethod] = Reads { json =>
    for {
      id <- (json \ "Id").validate[String]
      paymentType <- (json \ "Type").validate[String]
      numConsecutiveFailures <- (json \ "NumConsecutiveFailures").validateOpt[Int]
      paymentMethodStatus <- (json \ "PaymentMethodStatus").validateOpt[String]
      mandateId <- (json \ "MandateID").validateOpt[String]
      tokenId <- (json \ "TokenId").validateOpt[String]
      secondTokenId <- (json \ "SecondTokenId").validateOpt[String]
      payPalEmail <- (json \ "PaypalEmail").validateOpt[String]
      bankTransferType <- (json \ "BankTransferType").validateOpt[String]
      bankTransferAccountName <- (json \ "BankTransferAccountName").validateOpt[String]
      bankTransferAccountNumberMask <- (json \ "BankTransferAccountNumberMask").validateOpt[String]
      bankCode <- (json \ "BankCode").validateOpt[String]
      creditCardMaskNumber <- optString(json, "CreditCardMaskNumber")
      creditCardExpirationMonth <- optString(json, "CreditCardExpirationMonth")
      creditCardExpirationYear <- optString(json, "CreditCardExpirationYear")
      creditCardType <- (json \ "CreditCardType").validateOpt[String]
    } yield Queries.PaymentMethod(
      id = id,
      mandateId = mandateId,
      tokenId = tokenId,
      secondTokenId = secondTokenId,
      payPalEmail = payPalEmail,
      bankTransferType = bankTransferType,
      bankTransferAccountName = bankTransferAccountName,
      bankTransferAccountNumberMask = bankTransferAccountNumberMask,
      bankCode = bankCode,
      `type` = paymentType,
      creditCardNumber = creditCardMaskNumber.map(_.takeRight(4)),
      creditCardExpirationMonth = creditCardExpirationMonth,
      creditCardExpirationYear = creditCardExpirationYear,
      creditCardType = creditCardType,
      numConsecutiveFailures = numConsecutiveFailures,
      paymentMethodStatus = paymentMethodStatus,
    )
  }

  implicit val invoiceItemReads: Reads[Queries.InvoiceItem] = Reads { json =>
    for {
      id <- (json \ "Id").validate[String]
      chargeAmount <- (json \ "ChargeAmount").validate[Float]
      taxAmount <- (json \ "TaxAmount").validate[Float]
      serviceStartDate <- (json \ "ServiceStartDate").validate[String]
      serviceEndDate <- (json \ "ServiceEndDate").validate[String]
      chargeNumber <- (json \ "ChargeNumber").validate[String]
      productName <- (json \ "ProductName").validate[String]
      subscriptionId <- (json \ "SubscriptionId").validate[String]
    } yield Queries.InvoiceItem(
      id = id,
      price = chargeAmount + taxAmount,
      serviceStartDate = new LocalDate(serviceStartDate),
      serviceEndDate = new LocalDate(serviceEndDate),
      chargeNumber = chargeNumber,
      productName = productName,
      subscriptionId = subscriptionId,
    )
  }
}
