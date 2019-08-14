package services

import _root_.play.api.mvc.RequestHeader
import com.gu.identity.{IdapiConfig, play}
import org.http4s.Uri
import cats.implicits._
import com.gu.identity.model.User
import com.gu.identity.play.IdentityPlayAuthService
import com.gu.identity.play.IdentityPlayAuthService.UserCredentialsMissingError
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import scala.concurrent.{ExecutionContext, Future}

class IdentityAuthService(apiConfig: IdapiConfig)(implicit ec: ExecutionContext) extends AuthenticationService {

  val idApiUrl = Uri.unsafeFromString(apiConfig.url)

  val identityPlayAuthService = IdentityPlayAuthService.unsafeInit(idApiUrl, apiConfig.token, Some("membership"))

  def user(implicit requestHeader: RequestHeader): Future[Option[User]] = {
    getUser(requestHeader)
      .map(user => Option(user))
      .handleError { err =>
        if(err.isInstanceOf[UserCredentialsMissingError])
          SafeLogger.error(scrub"invalid request as no token provided - unable to authorize user - $err", err)
        else
          SafeLogger.warn(s"valid request but expired token so user must log in again - $err")

        None
      }
  }

  private def getUser(requestHeader: RequestHeader): Future[User] =
    identityPlayAuthService.getUserFromRequest(requestHeader)
      .map { case (_, user) => user }
      .unsafeToFuture()

}
