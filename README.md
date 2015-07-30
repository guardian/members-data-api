# Membership Attribute Service

The membership attribute service provides an API for managing and retrieving membership attributes associated with a user. 

## Endpoints

Access to endpoint for a specified id will be protected using an access token (TBD).

For access to the /me endpoints, valid GU_U and SC_GU_U must be provided in the Cookie request header. 

### Read enpoints

    GET /user-attributes/USER_ID/membership
    GET /user-attributes/me/membership
 
### Write endpoints
    
    PUT /user-attributes/USER_ID/membership
    PUT /user-attributes/me/membership
    
The request body must contain JSON in the following structure:

    {
    "membershipNumber": "1234567abcdef",
    "tier": "patron",
    "joinDate": "2015-04-01"
    }
    
A Content-Type of "application/json" must be provided.

### Responses

All responses will have a JSON body.

Success responses:

    {
      "status": "ok",
      "response": {
        "membershipNumber": "1234567abcdef",
        "tier": "patron",
        "joinDate": "2015-04-01"
      }
    }

Error responses:

    {
      "status": "error",
      "statusCode": 400,
      "errors": [
        {
          "message": "Bad Request",
          "friendlyMessage": "Json validation error List((obj.membershipNumber,List(ValidationError(List(error.expected.jsstring),WrappedArray()))))",
          "statusCode": 400
        }
      ]
    }
    
    
## Running Locally

Ensure that your ~/.aws/credentials file contains the following:

    [identity]
    aws_access_key_id=YOUR_ACCESS_KEY
    aws_secret_access_key=YOUR_SECRET_KEY
    
These credentials are required for accessing the DynamoDB table. When running tests, a local version of DynamoDB is used.

To start the service use:

    sbt api/run

The service will be starting on Play's default port of 9000.

