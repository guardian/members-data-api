package acceptance.data

import acceptance.data.Randoms.randomId
import services.salesforce.model.Contact
import org.joda.time.DateTime

object TestContact {
  def apply(
      identityId: String,
      salesforceContactId: String = randomId("salesforceContactId"),
      salesforceAccountId: String = randomId("salesforceAccountId"),
      lastName: String = "Smith",
      joinDate: DateTime = DateTime.now().minusDays(7),
      regNumber: Option[String] = None,
      title: Option[String] = None,
      firstName: Option[String] = None,
      mailingStreet: Option[String] = None,
      mailingCity: Option[String] = None,
      mailingState: Option[String] = None,
      mailingPostcode: Option[String] = None,
      mailingCountry: Option[String] = None,
      deliveryInstructions: Option[String] = None,
      recordTypeId: Option[String] = None,
  ): Contact =
    Contact(
      Some(identityId),
      regNumber,
      title,
      firstName,
      lastName,
      joinDate,
      salesforceContactId,
      salesforceAccountId,
      mailingStreet,
      mailingCity,
      mailingState,
      mailingPostcode,
      mailingCountry,
      deliveryInstructions,
      recordTypeId,
    )
}
