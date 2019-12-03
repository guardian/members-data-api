package models

import com.gu.salesforce.Contact
import play.api.libs.json.{Json, Writes}

case class DeliveryAddress(
    addressLine1: Option[String],
    addressLine2: Option[String],
    town: Option[String],
    region: Option[String],
    postcode: Option[String],
    country: Option[String]
)

object DeliveryAddress {
  implicit val writes: Writes[DeliveryAddress] = Json.writes[DeliveryAddress]

  def fromContact(contact: Contact): DeliveryAddress = {
    val addressLines = splitAddressLines(contact.mailingStreet)
    DeliveryAddress(
      addressLine1 = addressLines.map(_._1),
      addressLine2 = addressLines.map(_._2),
      town = contact.mailingCity,
      region = contact.mailingState,
      postcode = contact.mailingPostcode,
      country = contact.mailingCountry
    )
  }

  def splitAddressLines(addressLine: Option[String]): Option[(String, String)] =
    addressLine map { line =>
      val n = line.lastIndexOf(',')
      if (n == -1) (line, "")
      else (line.take(n).trim, line.drop(n + 1).trim)
    }
}
