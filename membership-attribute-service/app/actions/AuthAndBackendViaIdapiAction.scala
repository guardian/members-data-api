package actions

import com.gu.identity.auth.{AccessScope, IdapiUserCredentials, OktaUserCredentials}
import com.gu.identity.{IdapiService, RedirectAdviceResponse, SignedInRecently}
import components.{TouchpointBackends, TouchpointComponents}
import filters.IsTestUser
import models.{ApiError, UserFromToken}
import play.api.mvc.{ActionRefiner, Request, Result, Results}
import services.UserAndCredentials

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaIdapiAction(
    touchpointBackends: TouchpointBackends,
    howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
    isTestUser: IsTestUser,
    requiredScopes: List[AccessScope],
)(implicit
    ex: ExecutionContext,
) extends ActionRefiner[Request, AuthAndBackendRequest] {

  override protected val executionContext: ExecutionContext = ex

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] = {
    val userAndCredentials = touchpointBackends.normal.identityAuthService.userAndCredentials(request, requiredScopes)
    userAndCredentials flatMap {
      case Left(error) => Future.successful(Left(ApiError.apiErrorToResult(error)))
      case Right(UserAndCredentials(user, _: OktaUserCredentials)) => Future.successful(Right(oktaRefine(request, user)))
      case Right(UserAndCredentials(_, _: IdapiUserCredentials)) => idapiRefine(request)
    }
  }

  private def backend(displayName: Option[String]): TouchpointComponents =
    if (isTestUser(displayName)) {
      touchpointBackends.test
    } else {
      touchpointBackends.normal
    }

  private def oktaRefine[A](request: Request[A], user: UserFromToken): AuthAndBackendRequest[A] =
    new AuthAndBackendRequest(
      redirectAdvice = RedirectAdviceResponse(
        signInStatus = SignedInRecently,
        userId = Some(user.identityId),
        displayName = user.username,
        emailValidated = user.userEmailValidated,
        // Okta reauthentication redirect will be managed by the API client
        redirect = None,
      ),
      touchpoint = backend(user.username),
      request,
    )

  private def idapiRefine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] =
    touchpointBackends.normal.idapiService.RedirectAdvice
      .getRedirectAdvice(
        request.headers.get(IdapiService.HeaderNameCookie).getOrElse(""),
        request.headers.get(IdapiService.HeaderNameIdapiForwardedScope),
      )
      .map(redirectAdvice =>
        howToHandleRecencyOfSignedIn match {
          case Return401IfNotSignedInRecently if redirectAdvice.signInStatus != SignedInRecently =>
            Left(Results.Unauthorized.withHeaders(("X-GU-IDAPI-Redirect", redirectAdvice.redirect.map(_.url).getOrElse(""))))
          case _ =>
            val backendConf = backend(redirectAdvice.displayName)
            Right(new AuthAndBackendRequest[A](redirectAdvice, backendConf, request))
        },
      )
}
