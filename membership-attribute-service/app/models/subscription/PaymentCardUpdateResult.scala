package models.subscription

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsPath, Writes}

import scala.Function.unlift

sealed trait PaymentCardUpdateResult
case class CardUpdateSuccess(newPaymentCard: PaymentCard) extends PaymentCardUpdateResult
case class CardUpdateFailure(`type`: String, message: String, code: String) extends PaymentCardUpdateResult

object PaymentCardUpdateResult {
  implicit val cardUpdateSuccessWrites = Writes[CardUpdateSuccess] { cus =>
    PaymentCard.writes.writes(cus.newPaymentCard)
  }

  implicit val cardUpdateFailureWrites: Writes[CardUpdateFailure] = (
    (JsPath \ "type").write[String] and
      (JsPath \ "message").write[String] and
      (JsPath \ "code").write[String]
  )(unlift(CardUpdateFailure.unapply))
}
