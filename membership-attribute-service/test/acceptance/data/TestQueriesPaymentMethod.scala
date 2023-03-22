package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.zuora.soap.models.Queries.PaymentMethod

object TestQueriesPaymentMethod {
  def apply(
      id: String = randomId("paymentMethod"),
      mandateId: Option[String] = None,
      tokenId: Option[String] = None,
      secondTokenId: Option[String] = None,
      payPalEmail: Option[String] = None,
      bankTransferType: Option[String] = None,
      bankTransferAccountName: Option[String] = None,
      bankTransferAccountNumberMask: Option[String] = None,
      bankCode: Option[String] = None,
      paymentType: String,
      creditCardNumber: Option[String] = None,
      creditCardExpirationMonth: Option[String] = None,
      creditCardExpirationYear: Option[String] = None,
      creditCardType: Option[String] = None,
      numConsecutiveFailures: Option[Int] = None,
      paymentMethodStatus: Option[String] = None,
  ) = PaymentMethod(
    id: String,
    mandateId = mandateId,
    tokenId = tokenId,
    secondTokenId = secondTokenId,
    payPalEmail = payPalEmail,
    bankTransferType = bankTransferType,
    bankTransferAccountName = bankTransferAccountName,
    bankTransferAccountNumberMask = bankTransferAccountNumberMask,
    bankCode = bankCode,
    `type` = paymentType,
    creditCardNumber = creditCardNumber,
    creditCardExpirationMonth = creditCardExpirationMonth,
    creditCardExpirationYear = creditCardExpirationYear,
    creditCardType = creditCardType,
    numConsecutiveFailures = numConsecutiveFailures,
    paymentMethodStatus = paymentMethodStatus,
  )

}
