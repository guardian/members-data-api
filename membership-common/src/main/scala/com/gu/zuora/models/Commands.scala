package com.gu.zuora.models

import com.gu.i18n.Country
import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.api.PaymentGateway
import com.gu.zuora.models.Queries.Contact

object Commands {

  sealed trait PaymentMethod
  case class CreditCardReferenceTransaction(
      cardId: String,
      customerId: String,
      last4: String,
      cardCountry: Option[Country],
      expirationMonth: Int,
      expirationYear: Int,
      cardType: String,
  ) extends PaymentMethod
  case class BankTransfer(
      accountHolderName: String,
      accountNumber: String,
      sortCode: String,
      firstName: String,
      lastName: String,
      countryCode: String,
  ) extends PaymentMethod
  case class PayPalReferenceTransaction(baId: String, email: String) extends PaymentMethod

  case class CreatePaymentMethod(
      accountId: AccountId,
      paymentMethod: PaymentMethod,
      paymentGateway: PaymentGateway,
      billtoContact: Contact,
  )
}
