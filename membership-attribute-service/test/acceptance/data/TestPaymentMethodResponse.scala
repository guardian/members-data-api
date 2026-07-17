package acceptance.data

import org.joda.time.DateTime
import services.zuora.rest.ZuoraRestService.{PaymentMethodDetails, PaymentMethodResponse}

object TestPaymentMethodResponse {
  def apply(
      paymentMethodType: String,
      numConsecutiveFailures: Option[Int] = None,
      lastTransactionDateTime: Option[DateTime] = None,
      paymentMethodStatus: Option[String] = None,
      mandateId: Option[String] = None,
      bankTransferType: Option[String] = None,
      bankTransferAccountName: Option[String] = None,
      bankTransferAccountNumberMask: Option[String] = None,
      bankCode: Option[String] = None,
      creditCardNumber: Option[String] = None,
      creditCardExpirationMonth: Option[Int] = None,
      creditCardExpirationYear: Option[Int] = None,
      creditCardType: Option[String] = None,
      payPalEmail: Option[String] = None,
  ): PaymentMethodResponse = {
    val details: PaymentMethodDetails = paymentMethodType match {
      case "CreditCard" | "CreditCardReferenceTransaction" =>
        PaymentMethodDetails.Card(
          creditCardNumber,
          creditCardExpirationMonth,
          creditCardExpirationYear,
          creditCardType,
          isReferenceTransaction = paymentMethodType == "CreditCardReferenceTransaction",
        )
      case "BankTransfer" =>
        PaymentMethodDetails.BankTransfer(mandateId, bankTransferType, bankTransferAccountName, bankTransferAccountNumberMask, bankCode)
      case "PayPal" =>
        PaymentMethodDetails.PayPal(payPalEmail.getOrElse("test@paypal.com"))
      case other =>
        PaymentMethodDetails.Other(other)
    }
    PaymentMethodResponse(paymentMethodType, numConsecutiveFailures, lastTransactionDateTime, paymentMethodStatus, details)
  }
}
