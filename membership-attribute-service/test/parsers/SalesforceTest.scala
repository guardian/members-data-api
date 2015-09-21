package parsers

import models.MembershipAttributes
import org.specs2.mutable.Specification
import play.api.libs.json._

class SalesforceTest extends Specification {
  "contactReads" should {
    "deserialize a valid Salesforce contact JSON representation" in {
      val memberNumber = "1234"
      val memberTier = "Patron"
      val identityId = "4567"

      val raw_payload =
        s"""
          |{
          |  "attributes": {
          |    "type": "Contact",
          |    "url": "/services/data/v35.0/sobjects/Contact/123"
          |  },
          |  "Membership_Number__c": "$memberNumber",
          |  "Membership_Tier__c": "$memberTier",
          |  "IdentityID__c": "$identityId",
          |  "Id": "123"
          |}
        """.stripMargin

      val payload = Json.parse(raw_payload)

      payload.validate[MembershipAttributes](Salesforce.contactReads) shouldEqual JsSuccess(
        MembershipAttributes(
          userId = identityId,
          tier = memberTier,
          membershipNumber = memberNumber
        )
      )
    }
  }
}
