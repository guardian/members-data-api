package models

import play.api.libs.json.{Format, Json}
import services.salesforce.model.Contact

case class DeliveryAddress(
    addressLine1: Option[String],
    addressLine2: Option[String],
    town: Option[String],
    region: Option[String],
    postcode: Option[String],
    country: Option[String],
    addressChangeInformation: Option[String],
    instructions: Option[String],
)

object DeliveryAddress {
  implicit val format: Format[DeliveryAddress] = Json.format[DeliveryAddress]

  def fromContact(contact: Contact): DeliveryAddress = {
    val addressLines = splitAddressLines(contact.mailingStreet)
    DeliveryAddress(
      addressLine1 = addressLines.map(_._1),
      addressLine2 = addressLines.map(_._2),
      town = contact.mailingCity,
      region = contact.mailingState,
      postcode = contact.mailingPostcode,
      country = contact.mailingCountry,
      addressChangeInformation = None,
      instructions = contact.deliveryInstructions,
    )
  }

  def splitAddressLines(addressLine: Option[String]): Option[(String, String)] =
    addressLine map { line =>
      val n = line.lastIndexOf(',')
      if (n == -1) (line, "")
      else (line.take(n).trim, line.drop(n + 1).trim)
    }

  def mergeAddressLines(address: DeliveryAddress): Option[String] =
    (address.addressLine1, address.addressLine2) match {
      case (Some(line1), Some(line2)) => Some(s"${line1.trim},${line2.trim}")
      case (Some(line1), None) => Some(line1.trim)
      case (None, Some(line2)) => Some(line2.trim)
      case (None, None) => None
    }
}
