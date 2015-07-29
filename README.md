# User Attribute Service

The user attribute service provides an API for managing and retrieving membership attributes associated with a user. 

## Endpoints

    GET /user-attributes/USER_ID/membership
    GET /user-attributes/me/membership

To retrieve attributes for a specified id, clients must use an access token (TBD).

For access to the /me endpoint, GU_U and SC_GU_U cookies must be provided in the request.  
 
## Running Locally

Ensure that your ~/.aws/credentials file contains the following:

    [identity]
    aws_access_key_id=YOUR_ACCESS_KEY
    aws_secret_access_key=YOUR_SECRET_KEY
    
These credentials are required for accessing the DynamoDB table. When running tests, a local version of DynamoDB is used.

To start the service use:

    sbt api/run

The service will be starting on Play's default port of 9000.

