package acceptance.data

import org.joda.time.DateTime
import services.zuora.rest.ZuoraRestService.PaymentMethodResponse

object TestPaymentMethodResponse {
  def apply(
      paymentMethodType: String,
      numConsecutiveFailures: Int = 0,
      lastTransactionDateTime: Option[DateTime] = None,
      mandateId: Option[String] = None,
      tokenId: Option[String] = None,
      secondTokenId: Option[String] = None,
      payPalEmail: Option[String] = None,
      bankTransferType: Option[String] = None,
      bankTransferAccountName: Option[String] = None,
      bankTransferAccountNumberMask: Option[String] = None,
      bankCode: Option[String] = None,
      creditCardNumber: Option[String] = None,
      creditCardExpirationMonth: Option[Int] = None,
      creditCardExpirationYear: Option[Int] = None,
      creditCardType: Option[String] = None,
      paymentMethodStatus: Option[String] = None,
  ): PaymentMethodResponse = PaymentMethodResponse(
    numConsecutiveFailures = numConsecutiveFailures,
    paymentMethodType = paymentMethodType,
    lastTransactionDateTime = lastTransactionDateTime,
    mandateId = mandateId,
    tokenId = tokenId,
    secondTokenId = secondTokenId,
    payPalEmail = payPalEmail,
    bankTransferType = bankTransferType,
    bankTransferAccountName = bankTransferAccountName,
    bankTransferAccountNumberMask = bankTransferAccountNumberMask,
    bankCode = bankCode,
    creditCardNumber = creditCardNumber,
    creditCardExpirationMonth = creditCardExpirationMonth,
    creditCardExpirationYear = creditCardExpirationYear,
    creditCardType = creditCardType,
    paymentMethodStatus = paymentMethodStatus,
  )
}
