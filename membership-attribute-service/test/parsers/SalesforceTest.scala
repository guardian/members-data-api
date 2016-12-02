package parsers

import models.Attributes
import org.specs2.mutable.Specification
import parsers.Salesforce.{MembershipDeletion, MembershipUpdate, OrgIdMatchingError, ParsingError}

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

      val updateActionSeq = Seq(MembershipUpdate(Attributes("identity_id", "Supporter", Some("membership_number"))))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual updateActionSeq.right
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

      val updateActionSeq = Seq(MembershipUpdate(Attributes("identity_id", "Supporter", None)))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual updateActionSeq.right
    }

    "deserialize a valid update Salesforce Outbound Message which includes multiple notifications" in {
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
                <Id>notification_id1</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id1</sf:Id>
                  <sf:IdentityID__c>123</sf:IdentityID__c>
                  <sf:Membership_Number__c>12345</sf:Membership_Number__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
              <Notification>
                <Id>notification_id2</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id2</sf:Id>
                  <sf:IdentityID__c>321</sf:IdentityID__c>
                  <sf:Membership_Number__c>54321</sf:Membership_Number__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      val updateActionSeq = Seq(MembershipUpdate(Attributes("123", "Supporter", Some("12345"))), MembershipUpdate(Attributes("321", "Supporter", Some("54321"))))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual updateActionSeq.right
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

      val deleteActionSeq = Seq(MembershipDeletion("identity_id"))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual deleteActionSeq.right
    }

    "deserialize a Salesforce Outbound Message which includes a combination of delete and update notifications" in {
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
                <Id>notification_id1</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id1</sf:Id>
                  <sf:IdentityID__c>identity_id1</sf:IdentityID__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
              <Notification>
                <Id>notification_id2</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id2</sf:Id>
                  <sf:IdentityID__c>identity_id2</sf:IdentityID__c>
                </sObject>
              </Notification>
              <Notification>
                <Id>notification_id3</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>id3</sf:Id>
                  <sf:IdentityID__c>identity_id3</sf:IdentityID__c>
                  <sf:Membership_Number__c>membership_number</sf:Membership_Number__c>
                  <sf:Membership_Tier__c>Partner</sf:Membership_Tier__c>
                </sObject>
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      val sfNotifications = Seq(MembershipUpdate(Attributes("identity_id1", "Supporter", None)),
                                MembershipDeletion("identity_id2"),
                                MembershipUpdate(Attributes("identity_id3", "Partner", Some("membership_number"))))
      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual sfNotifications.right
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

    "returns an error if there are no Salesforce Objects" in {
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
              </Notification>
            </notifications>
          </soapenv:Body>
        </soapenv:Envelope>

      Salesforce.parseOutboundMessage(payload, orgId) shouldEqual ParsingError(s"No Salesforce Objects were found when parsing the message. \n $payload").left
    }
  }
}
