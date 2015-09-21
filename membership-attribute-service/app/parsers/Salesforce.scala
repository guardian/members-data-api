package parsers

import models.MembershipAttributes
import play.api.libs.json._
import play.api.libs.functional.syntax._

object Salesforce {
  val contactReads: Reads[MembershipAttributes] = (
    (JsPath \ "IdentityID__c").read[String] and
      (JsPath \ "Membership_Tier__c").read[String] and
      (JsPath \ "Membership_Number__c").read[String]
  )(MembershipAttributes.apply _)
}
