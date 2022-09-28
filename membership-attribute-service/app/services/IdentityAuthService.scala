package services

import _root_.play.api.mvc.RequestHeader
import cats.implicits._
import com.gu.identity.IdapiConfig
import com.gu.identity.auth._
import com.gu.identity.model.{PublicFields, StatusFields, User}
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.identity.play.IdentityPlayAuthService.UserCredentialsMissingError
import com.gu.monitoring.SafeLogger
import models.AccessClaims
import org.http4s.Uri

import scala.concurrent.{ExecutionContext, Future}

case class MDAPIUserClaims(
  primaryEmailAddress: String,
  identityId:String,
  username: Option[String],
  emailValidated: Option[Boolean]
  ) extends UserClaims

object MDAPIUserClaimsParser extends ClaimsParser[MDAPIUserClaims] {
  override def fromUnparsed(unparsedClaims: UnparsedClaims): Either[ValidationError, MDAPIUserClaims] = DefaultClaimsParser.fromUnparsed(unparsedClaims).map(
      defaultClaims =>
        MDAPIUserClaims(
          primaryEmailAddress = defaultClaims.primaryEmailAddress,
          identityId = defaultClaims.identityId,
          username = defaultClaims.username,
          emailValidated = unparsedClaims.getOptional[Boolean]("email_validated")
        )
    )

  override def fromUser(user: User): MDAPIUserClaims = MDAPIUserClaims(
    primaryEmailAddress = user.primaryEmailAddress,
    identityId = user.id,
    username = user.publicFields.username,
    emailValidated = user.statusFields.userEmailValidated
  )
}
class IdentityAuthService(apiConfig: IdapiConfig, oktaTokenVerifierConfig: OktaTokenVerifierConfig)(implicit ec: ExecutionContext) extends AuthenticationService {

  val idApiUrl = Uri.unsafeFromString(apiConfig.url)

  val identityPlayAuthService = {
    val idapiConfig = IdapiAuthConfig(idApiUrl, apiConfig.token, Some("membership"))
    IdentityPlayAuthService.unsafeInit(MDAPIUserClaimsParser,idapiConfig, oktaTokenVerifierConfig)
  }

  def user(implicit requestHeader: RequestHeader): Future[Option[AccessClaims]] = {
    getUser(requestHeader)
      .handleError { err =>
        err match {
          case UserCredentialsMissingError(_) =>
            // IdentityPlayAuthService throws an error if there is no SC_GU_U cookie or crypto auth token
            // frontend decides to make a request based on the existence of a GU_U cookie, so this case is expected.
            SafeLogger.info(s"unable to authorize user - no token or cookie provided")

           case OktaVerifierException(validationError: ValidationError) =>
            validationError match {
              case OktaValidationError(originalException) =>
                SafeLogger.warn(s"could not validate okta token - $validationError", originalException)
              case _ =>
                SafeLogger.warn(s"could not validate okta token - $validationError")
             }
          case err =>
            SafeLogger.warn(s"valid request but expired token or cookie so user must log in again - $err")
        }
        None
      }
  }

  private def getUser(requestHeader: RequestHeader): Future[Option[AccessClaims]] = {
    val scopes = List("profile", "email")
    identityPlayAuthService.getUserClaimsFromRequest(requestHeader, scopes)
      .map { case (_, claims) => {
        Some(AccessClaims(
          primaryEmailAddress = claims.primaryEmailAddress,
          id = claims.identityId,
          userName = claims.username.get, //this should be opti
          hasValidatedEmail = claims.username.getOrElse(false)
        ))

      }
      }.unsafeToFuture()
  }
}


