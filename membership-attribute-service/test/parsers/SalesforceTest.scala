package parsers

import models.Attributes
import org.specs2.mutable.Specification
import parsers.Salesforce.{MembershipDeletion, MembershipUpdate, OrgIdMatchingError}
import scalaz.syntax.either._

class SalesforceTest extends Specification {
  val orgId = "organizationId"
  "parseOutboundMessage" should {
    "deserialize a valid update Salesforce Outbound Message with MembershipNumber" in {
      val payload =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>{orgId}</OrganizationId>
              <ActionId>action_id</ActionId>
              <SessionId xsi:nil="true"/>
              <EnterpriseUrl>https://cs17.salesforce.com/services/Soap/c/34.0/enterprise_id</EnterpriseUrl>
              <PartnerUrl>https://cs17.salesforce.com/services/Soap/u/34.0/enterprise_id</PartnerUrl>
              <Notification>
                <Id>notification_id</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id</sf:Id>
                  <sf:IdentityID__c>identity_id</sf:IdentityID__c>
                  <sf:Membership_Number__c>membership_number</sf:Membership_Number__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      val updateAction = MembershipUpdate(Attributes("identity_id", "Supporter", Some("membership_number")))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual updateAction.right
    }

    "deserialize a valid update Salesforce Outbound Message without MembershipNumber" in {
      val payload =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>{orgId}</OrganizationId>
              <ActionId>action_id</ActionId>
              <SessionId xsi:nil="true"/>
              <EnterpriseUrl>https://cs17.salesforce.com/services/Soap/c/34.0/enterprise_id</EnterpriseUrl>
              <PartnerUrl>https://cs17.salesforce.com/services/Soap/u/34.0/enterprise_id</PartnerUrl>
              <Notification>
                <Id>notification_id</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id</sf:Id>
                  <sf:IdentityID__c>identity_id</sf:IdentityID__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      val updateAction = MembershipUpdate(Attributes("identity_id", "Supporter", None))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual updateAction.right
    }

    "deserialize a valid delete Salesforce Outbound Message" in {
      val payload =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>{orgId}</OrganizationId>
              <ActionId>action_id</ActionId>
              <SessionId xsi:nil="true"/>
              <EnterpriseUrl>https://cs17.salesforce.com/services/Soap/c/34.0/enterprise_id</EnterpriseUrl>
              <PartnerUrl>https://cs17.salesforce.com/services/Soap/u/34.0/enterprise_id</PartnerUrl>
              <Notification>
                <Id>notification_id</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id</sf:Id>
                  <sf:IdentityID__c>identity_id</sf:IdentityID__c>
                </sObject>
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      val deleteAction = MembershipDeletion("identity_id")
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual deleteAction.right
    }

    "returns an error if the organization does not match the expected one" in {
      val payload =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>wrong org</OrganizationId>
              <ActionId>action_id</ActionId>
              <SessionId xsi:nil="true"/>
              <EnterpriseUrl>https://cs17.salesforce.com/services/Soap/c/34.0/enterprise_id</EnterpriseUrl>
              <PartnerUrl>https://cs17.salesforce.com/services/Soap/u/34.0/enterprise_id</PartnerUrl>
              <Notification>
                <Id>notification_id</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id</sf:Id>
                  <sf:IdentityID__c>identity_id</sf:IdentityID__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual OrgIdMatchingError("wrong org").left
    }
  }
}
