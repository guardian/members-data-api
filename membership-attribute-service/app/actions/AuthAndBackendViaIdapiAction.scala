package actions

import com.gu.identity.SignedInRecently
import components.TouchpointBackends
import filters.AddGuIdentityHeaders
import play.api.mvc.{ActionRefiner, Request, Result, Results}

import scala.concurrent.{ExecutionContext, Future}

class AuthAndBackendViaIdapiAction(
  touchpointBackends: TouchpointBackends,
  howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
  scope: Option[String]
)(
  implicit ex: ExecutionContext
) extends ActionRefiner[Request, AuthAndBackendRequest] {

  override val executionContext = ex
  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] =
    touchpointBackends.normal.idapiService.RedirectAdvice.getRedirectAdvice(
      request.headers.get("Cookie").getOrElse(""),
      scope
    ).map(redirectAdvice => {

      val backendConf = if (AddGuIdentityHeaders.isTestUser(redirectAdvice.userId)) {
        touchpointBackends.test
      } else {
        touchpointBackends.normal
      }

      howToHandleRecencyOfSignedIn match {
        case Return401IfNotSignedInRecently if redirectAdvice.signInStatus != SignedInRecently =>
          Left(Results.Unauthorized) //TODO add Location header containing returnUrl
        case _ =>
          Right(new AuthAndBackendRequest[A](redirectAdvice, backendConf, request))
      }

    })
}