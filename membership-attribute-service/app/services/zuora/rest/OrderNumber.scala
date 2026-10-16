package services.zuora.rest

import play.api.libs.json.{JsError, JsString, JsSuccess, JsValue, Reads, Writes}

/** A Zuora order number, e.g. O-00005678. */
case class OrderNumber private (value: String) extends AnyVal

object OrderNumber {
  private val ValidFormat = "^[A-Za-z0-9-]+$".r

  def fromString(value: String): Either[String, OrderNumber] =
    Either.cond(ValidFormat.matches(value), new OrderNumber(value), s"invalid order number: '$value'")

  implicit val reads: Reads[OrderNumber] = Reads {
    case JsString(value) => fromString(value).fold(JsError(_), JsSuccess(_))
    case _ => JsError("order number must be a string")
  }

  implicit val writes: Writes[OrderNumber] = Writes(orderNumber => JsString(orderNumber.value): JsValue)
}
