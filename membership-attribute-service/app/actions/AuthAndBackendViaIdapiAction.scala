package actions

import com.gu.identity.auth.AccessScope
import com.gu.identity.{IdapiService, RedirectAdviceResponse, SignedInRecently}
import components.TouchpointBackends
import filters.AddGuIdentityHeaders
import play.api.mvc.{ActionRefiner, Request, Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaIdapiAction(
    touchpointBackends: TouchpointBackends,
    howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
    requiredScopes: List[AccessScope],
)(implicit
    ex: ExecutionContext,
) extends ActionRefiner[Request, AuthAndBackendRequest] {

  override protected val executionContext: ExecutionContext = ex

  private def hasAuthHeader[A](request: Request[A]): Boolean = request.headers.hasHeader("Authorization")

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] =
    if (hasAuthHeader(request)) {
      // Authenticate with Okta instead of Idapi, using the AuthAndBackendViaAuthLibAction
      AuthAndBackendViaAuthLibAction.refine(touchpointBackends, requiredScopes, request) { case (backendConf, authenticatedUser) =>
        new AuthAndBackendRequest(
          redirectAdvice = RedirectAdviceResponse(
            // Okta token lifecycle is managed elsewhere so can safely consider sign-in status always to be recent
            signInStatus = SignedInRecently,
            userId = Some(authenticatedUser.identityId),
            displayName = authenticatedUser.username,
            emailValidated = authenticatedUser.userEmailValidated,
            // Okta token lifecycle is managed elsewhere so never any need to redirect away here
            redirect = None,
          ),
          touchpoint = backendConf,
          request,
        )
      }
    } else idapiRefine(request)

  private def idapiRefine[A](request: Request[A])(implicit ex: ExecutionContext): Future[Either[Result, AuthAndBackendRequest[A]]] =
    touchpointBackends.normal.idapiService.RedirectAdvice
      .getRedirectAdvice(
        request.headers.get(IdapiService.HeaderNameCookie).getOrElse(""),
        request.headers.get(IdapiService.HeaderNameIdapiForwardedScope),
      )
      .map(redirectAdvice =>
        howToHandleRecencyOfSignedIn match {
          case Return401IfNotSignedInRecently if redirectAdvice.signInStatus != SignedInRecently =>
            Left(
              Results.Unauthorized.withHeaders(
                ("X-GU-IDAPI-Redirect", redirectAdvice.redirect.map(_.url).getOrElse("")),
              ),
            )
          case _ =>
            val backendConf = if (AddGuIdentityHeaders.isTestUser(redirectAdvice.displayName)) {
              touchpointBackends.test
            } else {
              touchpointBackends.normal
            }
            Right(new AuthAndBackendRequest[A](redirectAdvice, backendConf, request))
        },
      )
}
