# Membership Attribute Service

The membership attribute service provides an API for managing and retrieving membership attributes associated with a user. 

## Endpoints

Access to endpoint for a specified id will be protected using an access token (TBD). They are currently not protected at all.

For access to the /me endpoints, valid GU_U and SC_GU_U must be provided in the Cookie request header. 

### Read enpoints

    GET /user-attributes/me/membership
 
A Content-Type of "application/json" must be provided.

### Responses

All responses will have a JSON body.

Success responses:

    {
      "membershipNumber": "1234567abcdef",
      "tier": "patron"
    }

Error responses:

    {
      "message": "Bad Request",
      "details": "Detailed error message"
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

The service will be starting on 9100 and use the MembershipAttributes-DEV DynamoDB table.

## Metrics and Logs

There is a Membership Attributes Service radiator. This uses standard ELB and DynamoBB CloudWatch metrics for the CloudFormation stack in the chosen stage.

## Provisioning

The packer cloud formation template should be used to create an AMI with Oracle Java 8 installed. The base AMI which should be used with this is Ubuntu Trusty 14.04 (ami-acc41cdb).

The output AMI from packer should be used with the membership-attribute-service cloud formation template. 
