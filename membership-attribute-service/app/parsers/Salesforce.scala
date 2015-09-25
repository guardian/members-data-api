package parsers

import models.MembershipAttributes

import scala.xml._
import scalaz.Scalaz._
import scalaz.\/

object Salesforce {
  type UserId = String
  def parseOutboundMessage(payload: NodeSeq): String \/ MembershipAttributes = {
    implicit class NodeSeqOps(ns: NodeSeq) {
      def getTag(tag: String): String \/ Node =
        (ns \ tag).headOption \/> s"Error while parsing the outbound message: $tag not found.\n $payload"

      def getText(tag: String): String \/ String = getTag(tag).map(_.text)
    }

    for {
      obj <- (payload \\ "Notification").getTag("sObject")
      id <- obj.getText("IdentityID__c")
      tier <- obj.getText("Membership_Tier__c")
    } yield {
      val num = (obj \ "Membership_Number__c").headOption.map(_.text)
      MembershipAttributes(id, tier, num)
    }
  }
}
