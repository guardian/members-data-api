package models

import com.gu.identity.auth._
import com.gu.identity.model.User

import java.time.{Instant, ZoneId, ZonedDateTime}

/** Claims that are used to determine which resources the user is authorised to access.
  *
  * @param primaryEmailAddress
  *   primary email address
  * @param identityId
  *   Identity ID
  * @param username
  *   User name, a synonym for display name. Also used to determine if this is a test user.
  * @param userEmailValidated
  *   true iff the user has validated their email address
  * @param authTime
  *   The time the user was last authenticated.
  */
case class UserFromToken(
    primaryEmailAddress: String,
    identityId: String,
    username: Option[String] = None,
    userEmailValidated: Option[Boolean] = None,
    authTime: Option[ZonedDateTime], // optional because not available from Idapi
) extends AccessClaims

object UserFromTokenParser extends AccessClaimsParser[UserFromToken] {

  override def fromDefaultAndUnparsed(
      defaultClaims: DefaultAccessClaims,
      unparsedClaims: UnparsedClaims,
  ): Either[ValidationError, UserFromToken] = {
    def toUtcTime(unixTimeStamp: Long) = Instant.ofEpochSecond(unixTimeStamp).atZone(ZoneId.of("UTC"))
    for {
      authTime <- unparsedClaims.getRequired[Long]("auth_time")
    } yield UserFromToken(
      primaryEmailAddress = defaultClaims.primaryEmailAddress,
      identityId = defaultClaims.identityId,
      username = defaultClaims.username,
      userEmailValidated = unparsedClaims.getOptional[Boolean]("email_validated"),
      authTime = Some(toUtcTime(authTime)),
    )
  }

  override def fromUser(user: User): UserFromToken = UserFromToken(
    primaryEmailAddress = user.primaryEmailAddress,
    identityId = user.id,
    username = user.publicFields.username,
    userEmailValidated = user.statusFields.userEmailValidated,
    authTime = None,
  )
}
