package models

import play.api.libs.json._

case class UserDetails(firstName: Option[String], lastName: Option[String], email: String)

case class ProductsResponse(user: UserDetails, products: List[AccountDetails])

object ProductsResponse {
  implicit val userDetailsWrites: OWrites[UserDetails] = Json.writes[UserDetails]
  implicit val accountDetailsWrites: Writes[AccountDetails] = Writes[AccountDetails](_.toJson)
  implicit val writes: OWrites[ProductsResponse] = Json.writes[ProductsResponse]

  def from(user: UserFromToken, products: List[AccountDetails]) =
    ProductsResponse(
      user = UserDetails(
        firstName = user.firstName,
        lastName = user.lastName,
        email = user.primaryEmailAddress,
      ),
      products = products,
    )
}
