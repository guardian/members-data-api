package actions

import com.gu.identity.{IdapiService, SignedInRecently}
import components.TouchpointBackends
import filters.IsTestUser
import play.api.mvc.{ActionRefiner, Request, Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaIdapiAction(
    touchpointBackends: TouchpointBackends,
    howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
    isTestUser: IsTestUser,
)(implicit
    ex: ExecutionContext,
) extends ActionRefiner[Request, AuthAndBackendRequest] {

  override val executionContext = ex
  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] =
    touchpointBackends.normal.idapiService.RedirectAdvice
      .getRedirectAdvice(
        request.headers.get(IdapiService.HeaderNameCookie).getOrElse(""),
        request.headers.get(IdapiService.HeaderNameIdapiForwardedScope),
      )
      .map(redirectAdvice => {

        val backendConf = if (isTestUser(redirectAdvice.displayName)) {
          touchpointBackends.test
        } else {
          touchpointBackends.normal
        }

        howToHandleRecencyOfSignedIn match {
          case Return401IfNotSignedInRecently if redirectAdvice.signInStatus != SignedInRecently =>
            Left(
              Results.Unauthorized.withHeaders(
                ("X-GU-IDAPI-Redirect", redirectAdvice.redirect.map(_.url).getOrElse("")),
              ),
            )
          case _ =>
            Right(new AuthAndBackendRequest[A](redirectAdvice, backendConf, request))
        }

      })
}
