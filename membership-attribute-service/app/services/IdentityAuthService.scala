package services

import _root_.play.api.mvc.RequestHeader
import cats.implicits._
import com.gu.identity.IdapiConfig
import com.gu.identity.auth._
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.identity.play.IdentityPlayAuthService.UserCredentialsMissingError
import com.gu.monitoring.SafeLogger
import models.{AccessClaims, AccessClaimsParser}
import org.http4s.Uri

import scala.concurrent.{ExecutionContext, Future}

class IdentityAuthService(apiConfig: IdapiConfig, oktaTokenVerifierConfig: OktaTokenVerifierConfig)(implicit ec: ExecutionContext)
    extends AuthenticationService {

  val idApiUrl = Uri.unsafeFromString(apiConfig.url)

  val identityPlayAuthService = {
    val idapiConfig = IdapiAuthConfig(idApiUrl, apiConfig.token, Some("membership"))
    IdentityPlayAuthService.unsafeInit(AccessClaimsParser, idapiConfig, oktaTokenVerifierConfig)
  }

  def user(requiredScopes: List[String])(implicit requestHeader: RequestHeader): Future[Option[AccessClaims]] = {
    getUser(requestHeader, requiredScopes)
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

  private def getUser(requestHeader: RequestHeader, requiredScopes: List[String]): Future[Option[AccessClaims]] =
    identityPlayAuthService
      .getUserClaimsFromRequestLocallyOrWithIdapi(requestHeader, requiredScopes)
      .map {
        case (_: OktaUserCredentials, claims) =>
          SafeLogger.warn("Authorised by Okta token")
          Some(claims)
        case (_: IdapiUserCredentials, claims) =>
          SafeLogger.warn("Authorised by Idapi token")
          Some(claims)
      }
      .unsafeToFuture()
}
