package services

import _root_.play.api.mvc.RequestHeader
import cats.effect.IO
import cats.implicits._
import com.gu.identity.auth._
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.identity.play.IdentityPlayAuthService.UserCredentialsMissingError
import com.gu.monitoring.SafeLogging
import models.{ApiError, ApiErrors, UserFromToken}
import services.AuthenticationFailure.{Forbidden, Unauthorised}

import scala.concurrent.{ExecutionContext, Future}

class IdentityAuthService(identityPlayAuthService: IdentityPlayAuthService)(implicit ec: ExecutionContext)
    extends AuthenticationService
    with SafeLogging {

  def user(requiredScopes: List[AccessScope])(implicit requestHeader: RequestHeader): Future[Either[AuthenticationFailure, UserFromToken]] = {
    getUser(requestHeader, requiredScopes).attempt
      .map {
        case Left(UserCredentialsMissingError(_)) =>
          // IdentityPlayAuthService throws an error if there is no SC_GU_U cookie or crypto auth token
          // frontend decides to make a request based on the existence of a GU_U cookie, so this case is expected.
          Left(Unauthorised)

        case Left(OktaValidationException(validationError: ValidationError)) =>
          validationError match {
            case MissingRequiredScope(_) =>
              logger.warnNoPrefix(s"could not validate okta token - $validationError")
              Left(Forbidden)
            case OktaValidationError(originalException) =>
              logger.warnNoPrefix(
                s"could not validate okta token - $validationError. Path: ${requestHeader.path}. User-Agent: ${requestHeader.headers.get("User-Agent")}",
                originalException,
              )
              Left(Unauthorised)
            case _ =>
              logger.warnNoPrefix(s"could not validate okta token - $validationError")
              Left(Unauthorised)
          }

        case Left(err) =>
          logger.warnNoPrefix(s"valid request but expired token or cookie so user must log in again - $err")
          Left(Unauthorised)

        case Right(Some(user)) => Right(user)

        case Right(None) => Left(Unauthorised)
      }
  }

  /** If given request has valid credentials, returns a [[UserAndCredentials]]. This tells us the access claims and their source. Otherwise it returns
    * an [[ApiError]].
    *
    * @param requestHeader
    *   Request to extract claims from.
    * @param requiredScopes
    *   Permissions that token needs to access an endpoint, if it's an Okta token. This is ignored for Idapi credentials.
    */
  def userAndCredentials(requestHeader: RequestHeader, requiredScopes: List[AccessScope]): Future[Either[ApiError, UserAndCredentials]] =
    identityPlayAuthService
      .validateCredentialsFromRequest[UserFromToken](requestHeader, requiredScopes)
      .attempt
      .flatMap {
        // Request has Okta token but it's invalid
        case Left(OktaValidationException(error)) =>
          IO.pure(Left(ApiError(message = error.message, details = "", statusCode = error.suggestedHttpResponseCode)))
        // Request has invalid Idapi credentials
        case Left(UserCredentialsMissingError(_)) => IO.pure(Left(ApiErrors.unauthorized))
        // Something unexpected
        case Left(other) => IO.raiseError(other)
        // Request has valid Okta token
        case Right((credentials: OktaUserCredentials, user)) => IO.pure(Right(UserAndCredentials(user, credentials)))
        // Request has valid Idapi credentials
        case Right((credentials: IdapiUserCredentials, user)) => IO.pure(Right(UserAndCredentials(user, credentials)))
      }
      .unsafeToFuture()

  private def getUser(requestHeader: RequestHeader, requiredScopes: List[AccessScope]): Future[Option[UserFromToken]] =
    identityPlayAuthService
      .validateCredentialsFromRequest[UserFromToken](requestHeader, requiredScopes)
      .map {
        case (_: OktaUserCredentials, claims) =>
          Some(claims)
        case (_: IdapiUserCredentials, claims) =>
          import claims.logPrefix
          logger.warn("Authorised by Idapi token")
          Some(claims)
      }
      .unsafeToFuture()
}

/** A set of access claims and the source of the claims.
  */
case class UserAndCredentials(user: UserFromToken, credentials: UserCredentials)
