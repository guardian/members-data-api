package models.subscription

import play.api.libs.json.{Json, Writes}

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
    paymentMethodStatus: Option[String] = None,
) extends PaymentMethod

object PaymentCard {
  implicit val writes: Writes[PaymentCard] = Writes[PaymentCard] { paymentCard =>
    Json.obj("type" -> paymentCard.cardType.getOrElse[String]("unknown").replace(" ", "")) ++
      paymentCard.paymentCardDetails
        .map(details =>
          Json.obj(
            "last4" -> details.lastFourDigits,
            "expiryMonth" -> details.expiryMonth,
            "expiryYear" -> details.expiryYear,
          ),
        )
        .getOrElse(Json.obj("last4" -> "••••")) // effectively impossible to happen as this is used in a card update context
  }
}

case class PayPalMethod(
    email: String,
    numConsecutiveFailures: Option[Int] = None,
    paymentMethodStatus: Option[String] = None,
) extends PaymentMethod

case class GoCardless(
    mandateId: String,
    accountName: String,
    accountNumber: String,
    sortCode: String,
    numConsecutiveFailures: Option[Int] = None,
    paymentMethodStatus: Option[String] = None,
) extends PaymentMethod

case class Sepa(
    mandateId: String,
    accountName: String,
    accountNumber: String,
    numConsecutiveFailures: Option[Int] = None,
    paymentMethodStatus: Option[String] = None,
) extends PaymentMethod
