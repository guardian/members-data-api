package parsers

import models.MembershipAttributes

import scala.xml._
import scalaz.\/
import scalaz.syntax.std.option._

object Salesforce {
  def parseOutboundMessage(payload: NodeSeq): String \/ MembershipAttributes = {
    def err(msg: String) = s"Error while parsing the outbound message: $msg.\n $payload"

    for {
      obj <- (payload \\ "Notification" \ "sObject").headOption \/> err("sObject not found")
      tier <- (obj \ "Membership_Tier__c").headOption.map(_.text) \/> err("Membership_Tier__c not found")
      num <- (obj \ "Membership_Number__c").headOption.map(_.text) \/> err("Membership_Number__c not found")
    } yield MembershipAttributes(tier, num)
  }
}
