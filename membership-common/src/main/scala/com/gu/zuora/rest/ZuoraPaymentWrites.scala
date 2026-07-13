package com.gu.zuora.rest

import com.gu.zuora.soap.models.Commands
import play.api.libs.functional.syntax._
import play.api.libs.json._

/** JSON for the Zuora payment-method WRITE operations that ZuoraSoapService used to do over SOAP: account payment updates via PUT /v1/accounts/{id},
  * and payment-method creation via POST /v1/object/payment-method. The object API takes the same PascalCase zObject fields the SOAP create used, so
  * the field mapping mirrors com.gu.zuora.soap.writers.Command and the CreateCreditCardReferencePaymentMethod action.
  */
object ZuoraPaymentWrites {

  implicit val jsObjectWrites: Writes[JsObject] = Writes(identity)

  /** PUT /v1/accounts/{id}. A None defaultPaymentMethodId is written as an explicit JSON null, which Zuora treats as "clear the default" — the REST
    * equivalent of the SOAP fieldsToNull. See https://developer.zuora.com/api-references/api/operation/PUT_Account
    */
  case class AccountPaymentUpdate(defaultPaymentMethodId: Option[String], paymentGateway: String, autoPay: Boolean)
  implicit val accountPaymentUpdateWrites: Writes[AccountPaymentUpdate] = Writes { update =>
    Json.obj(
      "defaultPaymentMethodId" -> update.defaultPaymentMethodId.fold[JsValue](JsNull)(JsString(_)),
      "paymentGateway" -> update.paymentGateway,
      "autoPay" -> update.autoPay,
    )
  }

  /** POST /v1/object/payment-method returns {"Id": "...", "Success": true}. See
    * https://developer.zuora.com/api-references/api/operation/Object_POSTPaymentMethod
    */
  case class ObjectCreateResponse(id: String, success: Boolean)
  implicit val objectCreateResponseReads: Reads[ObjectCreateResponse] =
    ((__ \ "Id").read[String] and (__ \ "Success").read[Boolean])(ObjectCreateResponse.apply _)

  // Zuora only accepts a fixed set of CreditCardType values; anything else is omitted (matches the old SOAP action).
  private def normaliseCardType(cardType: String): Option[String] = cardType.toLowerCase.replaceAll(" ", "") match {
    case "mastercard" => Some("MasterCard")
    case "visa" => Some("Visa")
    case "amex" | "americanexpress" => Some("AmericanExpress")
    case "discover" => Some("Discover")
    case _ => None
  }

  def creditCardReference(
      accountId: String,
      cardId: String,
      customerId: String,
      last4: String,
      cardCountryAlpha2: Option[String],
      expirationMonth: Int,
      expirationYear: Int,
      cardType: String,
  ): JsObject = {
    val base = Json.obj(
      "AccountId" -> accountId,
      "Type" -> "CreditCardReferenceTransaction",
      "TokenId" -> cardId,
      "SecondTokenId" -> customerId,
      "CreditCardNumber" -> last4,
      "CreditCardExpirationMonth" -> expirationMonth,
      "CreditCardExpirationYear" -> expirationYear,
    )
    val withCountry = cardCountryAlpha2.fold(base)(country => base + ("CreditCardCountry" -> JsString(country)))
    normaliseCardType(cardType).fold(withCountry)(mapped => withCountry + ("CreditCardType" -> JsString(mapped)))
  }

  def paymentMethod(accountId: String, paymentMethod: Commands.PaymentMethod): JsObject = paymentMethod match {
    case card: Commands.CreditCardReferenceTransaction =>
      creditCardReference(
        accountId,
        card.cardId,
        card.customerId,
        card.last4,
        card.cardCountry.map(_.alpha2),
        card.expirationMonth,
        card.expirationYear,
        card.cardType,
      )
    case bankTransfer: Commands.BankTransfer =>
      Json.obj(
        "AccountId" -> accountId,
        "Type" -> "BankTransfer",
        "BankTransferType" -> "DirectDebitUK",
        "Country" -> bankTransfer.countryCode,
        "BankTransferAccountName" -> bankTransfer.accountHolderName,
        "BankTransferAccountNumber" -> bankTransfer.accountNumber,
        "BankCode" -> bankTransfer.sortCode,
        "FirstName" -> bankTransfer.firstName,
        "LastName" -> bankTransfer.lastName,
      )
    case payPal: Commands.PayPalReferenceTransaction =>
      Json.obj(
        "AccountId" -> accountId,
        "Type" -> "PayPal",
        "PaypalType" -> "ExpressCheckout",
        "PaypalBaid" -> payPal.baId,
        "PaypalEmail" -> payPal.email,
      )
  }
}
