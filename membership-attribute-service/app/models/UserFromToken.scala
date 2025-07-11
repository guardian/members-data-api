package models

import com.gu.identity.auth._
import com.gu.identity.model.User
import com.gu.monitoring.SafeLogger.LogPrefix

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
    firstName: Option[String] = None,
    lastName: Option[String] = None,
    username: Option[String] = None,
    userEmailValidated: Option[Boolean] = None,
    authTime: Option[ZonedDateTime], // optional because not available from Idapi
    oktaId: String,
) extends AccessClaims {
  implicit val logPrefix: LogPrefix = LogPrefix(identityId)
}

object UserFromToken {

  implicit val accessClaimsParser: AccessClaimsParser[UserFromToken] =
    (unparsedClaims: UnparsedClaims) => {
      def toUtcTime(unixTimeStamp: Int) = Instant.ofEpochSecond(unixTimeStamp).atZone(ZoneId.of("UTC"))

      for {
        authTime <- unparsedClaims.getRequired[Int]("auth_time")
        primaryEmailAddress <- AccessClaimsParser.primaryEmailAddress(unparsedClaims)
        identityId <- AccessClaimsParser.identityId(unparsedClaims)
        oktaId <- AccessClaimsParser.oktaId(unparsedClaims)
      } yield UserFromToken(
        primaryEmailAddress = primaryEmailAddress,
        identityId = identityId,
        username = AccessClaimsParser.userName(unparsedClaims),
        userEmailValidated = unparsedClaims.getOptional[Boolean]("email_validated"),
        firstName = unparsedClaims.getOptional[String]("first_name"),
        lastName = unparsedClaims.getOptional[String]("last_name"),
        authTime = Some(toUtcTime(authTime)),
        oktaId = oktaId,
      )
    }

  def fromIdapiUser(user: User): UserFromToken = {
    UserFromToken(
      primaryEmailAddress = user.primaryEmailAddress,
      identityId = user.id,
      username = user.publicFields.username,
      userEmailValidated = user.statusFields.userEmailValidated,
      firstName = user.privateFields.firstName,
      lastName = user.privateFields.secondName,
      authTime = None,
      oktaId = "", // no okta id in idapi, it's not used by MDAPI
    )
  }

}
case class MDAPIIdentityClaims(oktaId: String, primaryEmailAddress: String, identityId: String) extends IdentityClaims

object MDAPIIdentityClaims {

  implicit val identityClaimsParser: UserInfoResponseParser[MDAPIIdentityClaims] =
    (rawClaims: JsonString) => {
      for {
        oktaId <- UserInfoResponseParser.oktaId(rawClaims)
        primaryEmailAddress <- UserInfoResponseParser.primaryEmailAddress(rawClaims)
        identityId <- UserInfoResponseParser.identityId(rawClaims)
      } yield MDAPIIdentityClaims(oktaId, primaryEmailAddress, identityId)
    }

}
