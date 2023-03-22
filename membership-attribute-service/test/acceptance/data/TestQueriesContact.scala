package acceptance.data

import acceptance.data.Randoms.randomId
import com.gu.i18n.Country
import com.gu.zuora.soap.models.Queries.Contact

object TestQueriesContact {
  def apply(
      id: String = randomId("contactId"),
      firstName: String = randomId("first_name"),
      lastName: String = randomId("last_name"),
      postalCode: Option[String] = None,
      country: Option[Country] = None,
      email: Option[String] = None,
  ) = Contact(
    id = id,
    firstName = firstName,
    lastName = lastName,
    postalCode = postalCode,
    country = country,
    email = email,
  )
}
