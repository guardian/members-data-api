package models

import com.gu.monitoring.SafeLogger.LogPrefix
import play.api.libs.json._

case class UserDetails(firstName: Option[String], lastName: Option[String], email: String)

case class ProductsResponse(user: UserDetails, products: List[AccountDetails])

object ProductsResponse {
  implicit val userDetailsWrites: OWrites[UserDetails] = Json.writes[UserDetails]
  implicit def accountDetailsWrites(implicit logPrefix: LogPrefix): Writes[AccountDetails] = Writes[AccountDetails](_.toJson)
  implicit def writes(implicit logPrefix: LogPrefix): OWrites[ProductsResponse] = Json.writes[ProductsResponse]

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
