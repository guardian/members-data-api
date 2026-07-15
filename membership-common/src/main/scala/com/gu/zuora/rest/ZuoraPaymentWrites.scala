package com.gu.zuora.rest

import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.models.Commands
import play.api.libs.json._

/** JSON for the Zuora payment-method write operations: account payment updates via PUT /v1/accounts/{id}, and payment-method creation via POST
  * /v1/object/payment-method (which takes the PascalCase zObject fields of the payment method).
  */
object ZuoraPaymentWrites {

  /** PUT /v1/accounts/{id}. A None defaultPaymentMethodId is written as an explicit JSON null, which Zuora treats as clearing the default. See
    * https://developer.zuora.com/v1-api-reference/api/accounts/put_account
    */
  case class AccountPaymentUpdate(defaultPaymentMethodId: Option[String], paymentGateway: String, autoPay: Boolean)
  // Hand-written rather than derived: a None must serialise to an explicit JSON null (to clear the default), whereas Json.writes would omit it.
  implicit val accountPaymentUpdateWrites: Writes[AccountPaymentUpdate] = Writes { update =>
    Json.obj(
      "defaultPaymentMethodId" -> update.defaultPaymentMethodId.fold[JsValue](JsNull)(JsString(_)),
      "paymentGateway" -> update.paymentGateway,
      "autoPay" -> update.autoPay,
    )
  }

  /** The outcome of POST /v1/object/payment-method: {"Success": true, "Id": "..."} on success, {"Success": false, ...} on failure. Modelled as a
    * success/failure result so the Id is required exactly when the create succeeded (no Option to unwrap downstream). See
    * https://developer.zuora.com/api-references/older-api/operation/Object_POSTPaymentMethod
    */
  sealed trait ObjectCreateResult
  object ObjectCreateResult {
    case class Created(id: String) extends ObjectCreateResult
    case class Failed(reason: String) extends ObjectCreateResult

    implicit val reads: Reads[ObjectCreateResult] = (json: JsValue) =>
      (json \ "Success").validate[Boolean].flatMap {
        case true => (json \ "Id").validate[String].map(Created.apply)
        case false => JsSuccess(Failed((json \ "Errors").asOpt[JsValue].map(_.toString).getOrElse("no error detail")))
      }
  }

  /** The zObject payload for POST /v1/object/payment-method: the target account plus the payment-method business object. */
  case class CreatePaymentMethodObject(accountId: AccountId, paymentMethod: Commands.PaymentMethod)
  implicit val createPaymentMethodObjectWrites: Writes[CreatePaymentMethodObject] = Writes { request =>
    Json.obj("AccountId" -> request.accountId.get) ++ paymentMethodFields(request.paymentMethod)
  }

  private def paymentMethodFields(paymentMethod: Commands.PaymentMethod): JsObject = paymentMethod match {
    case card: Commands.CreditCardReferenceTransaction =>
      val base = Json.obj(
        "Type" -> "CreditCardReferenceTransaction",
        "TokenId" -> card.cardId,
        "SecondTokenId" -> card.customerId,
        "CreditCardNumber" -> card.last4,
        "CreditCardExpirationMonth" -> card.expirationMonth,
        "CreditCardExpirationYear" -> card.expirationYear,
        "CreditCardType" -> normaliseCardType(card.cardType),
      )
      card.cardCountry.fold(base)(country => base + ("CreditCardCountry" -> JsString(country.alpha2)))
    case bankTransfer: Commands.BankTransfer =>
      Json.obj(
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
        "Type" -> "PayPal",
        "PaypalType" -> "ExpressCheckout",
        "PaypalBaid" -> payPal.baId,
        "PaypalEmail" -> payPal.email,
      )
  }

  // Normalise the common brands to Zuora's CreditCardType casing; anything else is passed through with spaces removed.
  private def normaliseCardType(cardType: String): String = cardType.toLowerCase.replaceAll(" ", "") match {
    case "mastercard" => "MasterCard"
    case "visa" => "Visa"
    case "amex" | "americanexpress" => "AmericanExpress"
    case "discover" => "Discover"
    case _ => cardType.replaceAll(" ", "")
  }
}
