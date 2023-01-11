package actions

import com.gu.identity.auth.AccessScope
import com.gu.identity.{IdapiService, RedirectAdviceResponse, SignedInRecently}
import components.{TouchpointBackends, TouchpointComponents}
import filters.AddGuIdentityHeaders
import play.api.mvc.{ActionRefiner, Request, Result, Results}
import services.AuthenticationFailure

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaIdapiAction(
    touchpointBackends: TouchpointBackends,
    howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
    requiredScopes: List[AccessScope],
)(implicit
    ex: ExecutionContext,
) extends ActionRefiner[Request, AuthAndBackendRequest] {

  override protected val executionContext: ExecutionContext = ex

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] =
    oktaRefine(request).flatMap {
      case Left(Results.Unauthorized) => idapiRefine(request)
      case other => Future.successful(other)
    }

  private def backend(displayName: Option[String]): TouchpointComponents =
    if (AddGuIdentityHeaders.isTestUser(displayName)) {
      touchpointBackends.test
    } else {
      touchpointBackends.normal
    }

  private def oktaRefine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] = {
    val eventualUser = touchpointBackends.normal.identityAuthService.fetchUserFromOktaToken(request, requiredScopes)
    val x = eventualUser map {
      case Left(AuthenticationFailure.BadlyFormedToken) => Left(Results.BadRequest)
      case Left(AuthenticationFailure.Unauthorised) =>
        val z = Left(Results.Unauthorized)
        z
      case Left(AuthenticationFailure.Forbidden) => Left(Results.Forbidden)
      case Right(authenticatedUser) =>
        val y = Right(
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
            touchpoint = backend(authenticatedUser.username),
            request,
          ),
        )
        y
    }
    x
  }

  private def idapiRefine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] =
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
            val backendConf = backend(redirectAdvice.displayName)
            Right(new AuthAndBackendRequest[A](redirectAdvice, backendConf, request))
        },
      )
}
