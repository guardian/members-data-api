package models

/** Claims that are used to determine which resources the user is authorised to access.
  *
  * @param primaryEmailAddress
  *   primary email address
  * @param id
  *   Identity ID
  * @param userName
  *   User name, a synonym for display name. Also used to determine if this is a test user.
  * @param hasValidatedEmail
  *   true iff the user has validated their email address
  */
case class AccessClaims(
    primaryEmailAddress: String,
    id: String,
    userName: String, //TODO this should be optional
    hasValidatedEmail: Boolean, // TODO this is an option in the user object as well with a helper fuction to do getOrlElse(false)
)
