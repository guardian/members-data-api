# Membership Attribute Service

The membership attribute service provides an API for managing and retrieving membership attributes associated with a user. 

## Nginx configuration

Follow these [nginx setup](doc/nginx-setup.md) instructions

## Endpoints

For access to the /me endpoints, valid GU_U and SC_GU_U must be provided in the Cookie request header.

### GET /user-attributes/me/membership

Success responses:

    {
      "membershipNumber": "1234567abcdef",
      "tier": "patron",
      "userId": "123"
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

## Running Locally

Ensure that your ~/.aws/credentials file contains the following:

    [membership]
    aws_access_key_id=YOUR_ACCESS_KEY
    aws_secret_access_key=YOUR_SECRET_KEY
    
These credentials are required for accessing the DynamoDB table. When running tests, a local version of DynamoDB is used.

To start the service use:

```
    $ sbt
    > project membership-attribute-service
    > devrun
```

The service will be starting on 9400 and use the MembershipAttributes-DEV DynamoDB table.

## Loading initial dataset

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
