package models

import com.gu.identity.auth._
import com.gu.identity.model.User

/** Claims that are used to determine which resources the user is authorised to access.Ch
  *
  * @param primaryEmailAddress
  *   primary email address
  * @param identityId
  *   Identity ID
  * @param username
  *   User name, a synonym for display name. Also used to determine if this is a test user.
  * @param userEmailValidated
  *   true iff the user has validated their email address
  */
case class AccessClaims(
    primaryEmailAddress: String,
    identityId: String,
    username: Option[String] = None,
    userEmailValidated: Option[Boolean] = None,
) extends UserClaims

object AccessClaimsParser extends ClaimsParser[AccessClaims] {

  override def fromDefaultAndUnparsed(
      defaultClaims: DefaultUserClaims,
      unparsedClaims: UnparsedClaims,
  ): Either[ValidationError, AccessClaims] =
    Right(
      AccessClaims(
        primaryEmailAddress = defaultClaims.primaryEmailAddress,
        identityId = defaultClaims.identityId,
        username = defaultClaims.username,
        userEmailValidated = unparsedClaims.getOptional[Boolean]("email_validated"),
      ),
    )

  override def fromUser(user: User): AccessClaims = AccessClaims(
    primaryEmailAddress = user.primaryEmailAddress,
    identityId = user.id,
    username = user.publicFields.username,
    userEmailValidated = user.statusFields.userEmailValidated,
  )
}
