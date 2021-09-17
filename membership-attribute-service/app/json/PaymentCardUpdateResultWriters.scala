package json

import com.gu.memsub.{CardUpdateFailure, CardUpdateSuccess, PaymentCard}
import play.api.libs.json._
import play.api.libs.functional.syntax._

object PaymentCardUpdateResultWriters {

  implicit val paymentCardWrites: Writes[PaymentCard] = Writes[PaymentCard] { paymentCard =>
    Json.obj("type" -> paymentCard.cardType.getOrElse[String]("unknown").replace(" ", "")) ++
      paymentCard.paymentCardDetails
        .map(details =>
          Json.obj(
            "last4" -> details.lastFourDigits,
            "expiryMonth" -> details.expiryMonth,
            "expiryYear" -> details.expiryYear
          )
        )
        .getOrElse(Json.obj("last4" -> "••••")) // effectively impossible to happen as this is used in a card update context
  }

  implicit val cardUpdateSuccessWrites = Writes[CardUpdateSuccess] { cus =>
    paymentCardWrites.writes(cus.newPaymentCard)
  }

  implicit val cardUpdateFailureWrites: Writes[CardUpdateFailure] = (
    (JsPath \ "type").write[String] and
      (JsPath \ "message").write[String] and
      (JsPath \ "code").write[String]
  )(unlift(CardUpdateFailure.unapply))
}
