package com.gu.memsub

case class PaymentCardDetails(lastFourDigits: String, expiryMonth: Int, expiryYear: Int)

sealed trait PaymentMethod {
  val numConsecutiveFailures: Option[Int]
  val paymentMethodStatus: Option[String]
}
case class PaymentCard(
  isReferenceTransaction: Boolean,
  cardType: Option[String],
  paymentCardDetails: Option[PaymentCardDetails],
  numConsecutiveFailures: Option[Int] = None,
  paymentMethodStatus: Option[String] = None
) extends PaymentMethod

case class PayPalMethod(
  email : String,
  numConsecutiveFailures: Option[Int] = None,
  paymentMethodStatus: Option[String] = None
) extends PaymentMethod

case class GoCardless(
  mandateId: String,
  accountName: String,
  accountNumber: String,
  sortCode: String,
  numConsecutiveFailures: Option[Int] = None,
  paymentMethodStatus: Option[String] = None
) extends PaymentMethod

case class Sepa(
  mandateId: String,
  accountName: String,
  accountNumber: String,
  numConsecutiveFailures: Option[Int] = None,
  paymentMethodStatus: Option[String] = None
) extends PaymentMethod