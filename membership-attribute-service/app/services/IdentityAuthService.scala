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
import configuration.Config

import scala.concurrent.{ExecutionContext, Future}

//TODO DRY refactoring
class IdentityAuthService(apiConfig: IdapiConfig)(implicit ec: ExecutionContext) extends AuthenticationService {

  //  val url = IdapiConfig.from(Config.)
  val idApiUrl = Uri.unsafeFromString(apiConfig.url)

  val identityPlayAuthService = IdentityPlayAuthService.unsafeInit(idApiUrl, apiConfig.token, Some("membership"))

  // Utilises the custom error introduced in: https://github.com/guardian/identity/pull/1578
  // This error indicates a user was unable to authenticate because they were missing credentials
  // i.e. they were not signed in.
  def isUserNotSignedInError(err: Throwable): Boolean =
    err.isInstanceOf[UserCredentialsMissingError]


  def user(implicit requestHeader: RequestHeader): Future[Option[User]] = {
    getUser(requestHeader)
      .map(user => Option(user))
      .handleError { err =>
        if(isUserNotSignedInError(err))
          SafeLogger.info(s"unable to authorize user - $err")
        else
          SafeLogger.error(scrub"unable to authorize user - $err")

        None
      }
  }

  def getUser(requestHeader: RequestHeader): Future[User] =
    identityPlayAuthService.getUserFromRequest(requestHeader)
      .map { case (_, userId) => userId }
      .unsafeToFuture()

}
