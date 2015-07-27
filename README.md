# User Attribute Service

The user attribute service provides an API for managing and attributes associated with a user and a means of retrieving them. 

Only membership attributes are currently supported.

## Endpoints

GET /user-attributes/USER_ID/membership
GET /user-attributes/me/membership

To retrieve attributes for a specified id, clients must use a an access token (TBD).

For access to the /me endpoint, GU_U and SC_GU_U cookies must be provided in the request.