package actions

import com.gu.identity.auth.AccessScope
import components.TouchpointBackends
import filters.IsTestUser
import play.api.mvc.{ActionRefiner, Request, Result, Results}
import services.AuthenticationFailure

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaAuthLibAction(
    touchpointBackends: TouchpointBackends,
    requiredScopes: List[AccessScope],
    isTestUser: IsTestUser,
)(implicit
    ex: ExecutionContext,
) extends ActionRefiner[Request, AuthenticatedUserAndBackendRequest] {
  override val executionContext = ex

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthenticatedUserAndBackendRequest[A]]] = {
    touchpointBackends.normal.identityAuthService.user(requiredScopes)(request) map {
      case Left(AuthenticationFailure.Unauthorised) => Left(Results.Unauthorized)
      case Left(AuthenticationFailure.Forbidden) => Left(Results.Forbidden)
      case Right(authenticatedUser) =>
        val backendConf = if (isTestUser(authenticatedUser.username)) {
          touchpointBackends.test
        } else {
          touchpointBackends.normal
        }
        Right(new AuthenticatedUserAndBackendRequest[A](authenticatedUser, backendConf, request))
    }
  }
}
