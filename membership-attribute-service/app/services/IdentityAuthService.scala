package services

import _root_.play.api.mvc.RequestHeader
import cats.implicits._
import com.gu.identity.IdapiConfig
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.identity.play.IdentityPlayAuthService.UserCredentialsMissingError
import com.gu.monitoring.SafeLogger
import models.AccessClaims
import org.http4s.Uri

import scala.concurrent.{ExecutionContext, Future}

class IdentityAuthService(apiConfig: IdapiConfig)(implicit ec: ExecutionContext) extends AuthenticationService {

  val idApiUrl = Uri.unsafeFromString(apiConfig.url)

  val identityPlayAuthService = IdentityPlayAuthService.unsafeInit(idApiUrl, apiConfig.token, Some("membership"))

  def user(implicit requestHeader: RequestHeader): Future[Option[AccessClaims]] = {
    getUser(requestHeader)
      .map(user => Option(user))
      .handleError { err =>
        if (err.isInstanceOf[UserCredentialsMissingError])
          // IdentityPlayAuthService throws an error if there is no SC_GU_U cookie or crypto auth token
          // frontend decides to make a request based on the existence of a GU_U cookie, so this case is expected.
          SafeLogger.info(s"unable to authorize user - no token or cookie provided")
        else
          SafeLogger.warn(s"valid request but expired token or cookie so user must log in again - $err")

        None
      }
  }

  private def getUser(requestHeader: RequestHeader): Future[AccessClaims] =
    identityPlayAuthService
      .getUserFromRequest(requestHeader)
      .map { case (_, user) =>
        AccessClaims(
          primaryEmailAddress = user.primaryEmailAddress,
          id = user.id,
          userName = user.publicFields.username.getOrElse(""),
          hasValidatedEmail = user.statusFields.isUserEmailValidated,
        )
      }
      .unsafeToFuture()

}
