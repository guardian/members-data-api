package json

import com.gu.memsub.{CardUpdateFailure, CardUpdateSuccess, PaymentCard}
import play.api.libs.json._
import play.api.libs.functional.syntax._

object PaymentCardUpdateResultWriters {

  implicit val paymentCardWrites: Writes[PaymentCard] = (
    (JsPath \ "type").write[String] and
    (JsPath \ "last4").write[String]
  )(unlift(PaymentCard.unapply))

  implicit val cardUpdateSuccessWrites = Writes[CardUpdateSuccess] { cus =>
    paymentCardWrites.writes(cus.newPaymentCard)
  }

  implicit val cardUpdateFailureWrites: Writes[CardUpdateFailure] = (
    (JsPath \ "type").write[String] and
    (JsPath \ "message").write[String] and
    (JsPath \ "code").write[String]
  )(unlift(CardUpdateFailure.unapply))
}
