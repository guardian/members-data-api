package actions

import com.gu.identity.auth.AccessScope
import components.{TouchpointBackends, TouchpointComponents}
import filters.AddGuIdentityHeaders
import models.UserFromToken
import play.api.mvc.{ActionRefiner, Request, Result, Results, WrappedRequest}
import services.AuthenticationFailure

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaAuthLibAction(touchpointBackends: TouchpointBackends, requiredScopes: List[AccessScope])(implicit ex: ExecutionContext)
    extends ActionRefiner[Request, AuthenticatedUserAndBackendRequest] {
  override val executionContext = ex

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthenticatedUserAndBackendRequest[A]]] =
    AuthAndBackendViaAuthLibAction.refine(touchpointBackends, requiredScopes, request) { case (backendConf, authenticatedUser) =>
      new AuthenticatedUserAndBackendRequest(Right(authenticatedUser), backendConf, request)
    }
}

object AuthAndBackendViaAuthLibAction {

  /** Enriches a request with additional user details if the client has permission to call the endpoint being requested.
    *
    * @param touchpointBackends
    *   Gives access to various upstream APIs
    * @param requiredScopes
    *   Scopes that an access token must have to call the endpoint being requested
    * @param request
    *   Request for a protected endpoint
    * @param refined
    *   Function giving enriched user details if authorisation is successful
    * @tparam A
    *   Type of incoming request
    * @tparam R
    *   Type of request after enrichment with additional user details
    * @return
    *   Enriched request if authorisation is successful. Otherwise a failure result, which can be [[Results.Unauthorized]] if authentication fails or
    *   [[Results.Forbidden]] if client doesn't have permission to call the endpoint being requested.
    */
  def refine[A, R[_] <: WrappedRequest[_]](touchpointBackends: TouchpointBackends, requiredScopes: List[AccessScope], request: Request[A])(
      refined: (TouchpointComponents, UserFromToken) => R[A],
  )(implicit ec: ExecutionContext): Future[Either[Result, R[A]]] = {
    touchpointBackends.normal.identityAuthService.user(requiredScopes)(request) map {
      case Left(AuthenticationFailure.Unauthorised) => Left(Results.Unauthorized)
      case Left(AuthenticationFailure.Forbidden) => Left(Results.Forbidden)
      case Right(authenticatedUser) =>
        val backendConf = if (AddGuIdentityHeaders.isTestUser(authenticatedUser.username)) {
          touchpointBackends.test
        } else {
          touchpointBackends.normal
        }
        Right(refined(backendConf, authenticatedUser))
    }
  }
}
