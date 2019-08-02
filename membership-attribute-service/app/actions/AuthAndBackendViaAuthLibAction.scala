package actions

import com.gu.identity.model.User
import components.{TouchpointBackends, TouchpointComponents}
import filters.AddGuIdentityHeaders
import play.api.mvc.{ActionRefiner, Request, Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaAuthLibAction(touchpointBackends: TouchpointBackends)(implicit ex: ExecutionContext) extends ActionRefiner[Request, AuthenticatedUserAndBackendRequest] {
  override val executionContext = ex

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthenticatedUserAndBackendRequest[A]]] = {
    touchpointBackends.normal.identityAuthService.user(request) map { user: Option[User] =>
      val maybeUsername = user.flatMap(_.publicFields.username)

      val backendConf: TouchpointComponents = if (AddGuIdentityHeaders.isTestUser(maybeUsername)) {
        touchpointBackends.test
      } else {
        touchpointBackends.normal
      }

      user match {
        case Some(authenticatedUser) => Right(new AuthenticatedUserAndBackendRequest[A](Option(authenticatedUser), backendConf, request))
        case None => Left(Results.Unauthorized)
      }
    }
  }
}