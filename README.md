# Membership Attribute Service

The membership attribute service provides an API for managing and retrieving membership attributes associated with a user. It runs on https://members-data-api.theguardian.com/

## Setting it up locally

Run `./setup.sh` in `nginx/`.

Add the following line to the hosts file:

`127.0.0.1   members-data-api.thegulocal.com`

Download the config (you may need to `brew install awscli` to get the command).
`aws s3 cp s3://members-data-api-private/DEV/members-data-api.private.conf /etc/gu/ --profile membership`

## Running Locally

Get Janus credentials for membership.

To start the service run `./start-api.sh`

The service will be running on 9400 and use the MembershipAttributes-DEV DynamoDB table.

go to https://members-data-api.thegulocal.com/user-attributes/me/mma-membership

## Running tests

run `sbt` and then test.  It will download a dynamodb table from S3 and use that.  _Tip_: watch out for firewalls blocking the download, you may need to turn them off to stop it scanning the file.

## Testing manually

A good strategy for testing your stuff is to run a local identity-frontend, membership-frontend and members-data-api.  Then sign up for membership and hit the above url, which should return the right JSON structure.

The /me endpoints use the GU_U and SC_GU_U from the Cookie request header.

### Identity Frontend

Identity frontend is split between [new (profile-origin)](https://github.com/guardian/identity-frontend) and old (profile), which is the identity project in [frontend](https://github.com/guardian/frontend). Only profile uses the membership-attribute-service. Make sure that it's pointing at your local instance.

    devOverrides{
             guardian.page.userAttributesApiUrl="https://members-data-api.thegulocal.com/user-attributes"
             id.members-data-api.url="https://members-data-api.thegulocal.com/"
    }
 
## API Docs

### GET /user-attributes/me

#### User is a member

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "1234",
        "wallet": {
            "membershipCard": {
                "last4": "4242",
                "expirationMonth": 4,
                "expirationYear": 2024,
                "forProduct": "membership"
            }
        },
        "membershipJoinDate": "2017-06-26",
        "contentAccess": {
            "member": true,
            "paidMember": true,
            "recurringContributor": false
        }
    }

#### User is a contributor and not a member 
    
    {
        "userId":"xxxx",
        "recurringContributionPaymentPlan":"Monthly Contribution",
        "contentAccess": {
            "member":false,
            "paidMember":false,
            "recurringContributor":true
        }
    }


#### User is not a member and not a contributor
    
    {
        "message":"Not found",
        "details":"Could not find user in the database",
        "statusCode":404
    }


#### User is a member and a contributor

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "324154",
        "wallet": {
            "membershipCard": {
                "last4": "4242",
                "expirationMonth": 4,
                "expirationYear": 2024,
                "forProduct": "membership"
            }
        },
        "recurringContributionPaymentPlan": "Monthly Contribution",
        "membershipJoinDate": "2017-06-26",
        "contentAccess": {
            "member": true,
            "paidMember": true,
            "recurringContributor": true
        }
    }


### GET /user-attributes/me/membership


#### User is a member

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "1234",
        "contentAccess": {
            "member": true,
            "paidMember": true
         }
    }

#### User is a contributor and not a member 

    {
        "message":"Not found",
        "details":"User was found but they are not a member",
        "statusCode":404
    }


#### User is a member and contributor

    {
        "userId": "xxxx",
        "tier": "Supporter",
        "membershipNumber": "1234",
        "contentAccess": {
            "member": true,
            "paidMember": true
        }
    }

#### User is not a member and not a contributor

    {
        "message":"Not found",
        "details":"Could not find user in the database",
        "statusCode":404
    }


### GET /user-attributes/me/features
Responses:

    {
      "adfree": true,
      "adblockMessage": false,
      "userId": "123"
    }

## Loading initial dataset - FIXME when would you want to do that?

- Make sure that the outbound messages are pointing to your instance

- Truncate your DB

- Download a CSV report file from Salesforce containing the required fields. The header should be

```
    "IdentityID","Membership Number","Membership Tier","Last Modified Date"
```

- Increase the write throughput of you dynamoDB instance (100 should be enough)

- run `sbt -Dconfig.resource=[DEV|PROD].conf ";project membership-attribute-service ;batch-load <path/to/csvfile.csv>"`

- Decrease the write throughput of you dynamoDB instance to 1

- Check that no records have been altered during the time the command takes to run. It's easy to check via the Membership History object in Salesforce.

## Metrics and Logs

There is a Membership Attributes Service radiator. This uses standard ELB and DynamoBB CloudWatch metrics for the CloudFormation stack in the chosen stage.

## Provisioning

The packer cloud formation template should be used to create an AMI with Oracle Java 8 installed. The base AMI which should be used with this is Ubuntu Trusty 14.04 (ami-acc41cdb).

The output AMI from packer should be used with the membership-attribute-service cloud formation template.
 
## Testing SalesForce hook
 
In order to trigger the SalesForce hook in `DEV`, you have to generate a `POST` request to the SalesForce endpoint in Members Data API. 
You can do that by executing the following command:

`curl -X POST  https://members-data-api.thegulocal.com/salesforce-hook?secret=secret-key -H "Content-Type:application/xml" -d @salesforce-test.xml`

The `salesforce.xml` should looks like:

    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
            <notifications xmlns="http://soap.sforce.com/2005/09/outbound">
              <OrganizationId>orgIdxxxxxxxxxxxxx</OrganizationId>
              <ActionId>action_id</ActionId>
              <SessionId xsi:nil="true"/>
              <EnterpriseUrl>https://cs17.salesforce.com/services/Soap/c/34.0/enterprise_id</EnterpriseUrl>
              <PartnerUrl>https://cs17.salesforce.com/services/Soap/u/34.0/enterprise_id</PartnerUrl>
              <Notification>
                <Id>notification_id</Id>
                <sObject xsi:type="sf:Contact" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
                  <sf:Id>exampleid</sf:Id>
                  <sf:IdentityID__c>PUT ZUORA ID HERE</sf:IdentityID__c>
                  <sf:Membership_Number__c>1234</sf:Membership_Number__c>
                  <sf:Membership_Tier__c>Supporter</sf:Membership_Tier__c>
                </sObject>
              </Notification>
            </notifications>
        </soapenv:Body>
    </soapenv:Envelope>
