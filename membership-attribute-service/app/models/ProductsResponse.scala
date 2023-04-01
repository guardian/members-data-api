package models

import com.gu.salesforce.Contact
import play.api.libs.json._

case class UserDetails(firstName: Option[String], lastName: Option[String], email: String)

case class ProductsResponse(user: UserDetails, products: List[AccountDetails])

object ProductsResponse {
  implicit val userDetailsWrites = Json.writes[UserDetails]
  implicit val accountDetailsWrites = Writes[AccountDetails](_.toJson)
  implicit val writes = Json.writes[ProductsResponse]

  def from(user: UserFromToken, contact: Contact, products: List[AccountDetails]) =
    ProductsResponse(
      user = UserDetails(
        firstName = contact.firstName,
        lastName = Some(contact.lastName).filterNot(_.isEmpty),
        email = user.primaryEmailAddress,
      ),
      products = products,
    )
}
