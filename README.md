# Membership Attribute Service

The membership attribute service provides an API for managing and retrieving membership attributes associated with a user. It runs on https://members-data-api.theguardian.com/

## Setting it up locally

Run `./setup.sh` in `nginx/`.

Add the following line to the hosts file:

`127.0.0.1   members-data-api.thegulocal.com`

Download the config (you may need to `brew install awscli` to get the command.
`aws s3 cp s3://members-data-api-private/DEV/members-data-api.private.conf /etc/gu/ --profile membership`

## Running Locally

Get Janus credentials for membership.

To start the service run ./start-api.sh

The service will be running on 9400 and use the MembershipAttributes-DEV DynamoDB table.

go to https://members-data-api.thegulocal.com/user-attributes/me/mma-membership

## Running tests

run sbt and then test.  It will download a dynamodb table from S3 and use that.  Tip: watch out for firewalls blocking the download, you may need to turn them off to stop it scanning the file.

## Testing manually

A good strategy for testing your stuff is to run a local identity-frontend, membership-frontend and members-data-api.  Then sign up for membership and hit the above url, which should return the right JSON structure.

The /me endpoints use the GU_U and SC_GU_U from the Cookie request header.

###Identity Frontend

Identity frontend is split between [new (profile-origin)](https://github.com/guardian/identity-frontend) and old (profile), which is the identity project in [frontend](https://github.com/guardian/frontend). Only profile uses the membership-attribute-service. Make sure that it's pointing at your local instance.

    devOverrides{
             guardian.page.userAttributesApiUrl="https://members-data-api.thegulocal.com/user-attributes"
             id.members-data-api.url="https://members-data-api.thegulocal.com/"
    }
 
### GET /user-attributes/me/membership

Success responses:

    {
      "membershipNumber": "1234567abcdef",
      "tier": "patron",
      "userId": "123",
      "contentAccess":{ "member":true, "paidMember":true }
    }

Error responses:

    {
      "message": "Bad Request",
      "details": "Detailed error message"
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
