package parsers

import models.MembershipAttributes
import org.specs2.mutable.Specification
import scalaz.syntax.either._

class SalesforceTest extends Specification {
  "parseOutboundMessage" should {
    "deserialize a valid Salesforce Outbound Message with MembershipNumber" in {
      val payload =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>org_id</OrganizationId>
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

      Salesforce.parseOutboundMessage(payload) shouldEqual MembershipAttributes("identity_id", "Supporter", Some("membership_number")).right
    }

    "deserialize a valid Salesforce Outbound Message without MembershipNumber" in {
      val payload =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>org_id</OrganizationId>
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

      Salesforce.parseOutboundMessage(payload) shouldEqual MembershipAttributes("identity_id", "Supporter", None).right
    }
  }
}
