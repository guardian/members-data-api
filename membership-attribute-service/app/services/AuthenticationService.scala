package services

import com.gu.identity.auth.AccessScope
import models.UserFromToken
import play.api.mvc.RequestHeader

import scala.concurrent.Future

trait AuthenticationService {
  def user(requiredScopes: List[AccessScope])(implicit request: RequestHeader): Future[Either[AuthenticationFailure, UserFromToken]]
}

/** See [[https://auth0.com/blog/forbidden-unauthorized-http-status-codes/]] for rationale.
  */
sealed trait AuthenticationFailure

object AuthenticationFailure {

  /** Client has provided no credentials or invalid credentials. Should give a 401 response.
    */
  case object Unauthorised extends AuthenticationFailure

  /** Client has valid credentials but not enough privileges to perform the action. Should give a 403 response.
    */
  case object Forbidden extends AuthenticationFailure

  /** Token is badly formed, eg. claims don't match scopes. Should give a 400 response.
    */
  case object BadlyFormedToken extends AuthenticationFailure
}
