package actions

import com.gu.identity.auth.AccessScope
import components.{TouchpointBackends, TouchpointComponents}
import filters.AddGuIdentityHeaders
import models.UserFromToken
import play.api.mvc.{ActionRefiner, Request, Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaAuthLibAction(touchpointBackends: TouchpointBackends, requiredScopes: List[AccessScope])(implicit ex: ExecutionContext)
    extends ActionRefiner[Request, AuthenticatedUserAndBackendRequest] {
  override val executionContext = ex

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthenticatedUserAndBackendRequest[A]]] = {
    touchpointBackends.normal.identityAuthService.user(requiredScopes)(request) map { user: Option[UserFromToken] =>
      val backendConf: TouchpointComponents = if (AddGuIdentityHeaders.isTestUser(user.flatMap(_.username))) {
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
