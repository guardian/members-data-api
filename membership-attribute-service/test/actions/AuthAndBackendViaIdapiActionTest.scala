package actions

import components.{TouchpointBackends, TouchpointComponents}
import models.AccessScope.readSelf
import models.UserFromToken
import org.mockito.Mockito.when
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.mvc.{AnyContent, Results}
import play.api.test.FakeRequest
import services.{AuthenticationFailure, IdentityAuthService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class AuthAndBackendViaIdapiActionTest extends Specification with Mockito {

  "AuthAndBackendViaIdapiAction.refine" should {

//    "use Okta token to build a refined request when an Okta token is present" in {
//
//      val request = FakeRequest()
//      val requiredScopes = List(readSelf)
//
//      val user = mock[UserFromToken]
//      when(user.username).thenReturn(None)
//
//      val authService = mock[IdentityAuthService]
//      when(authService.fetchUserFromOktaToken(request, requiredScopes)).thenReturn(Future.successful(Right(user)))
//
//      val components = mock[TouchpointComponents]
//      when(components.identityAuthService).thenReturn(authService)
//
//      val backends = mock[TouchpointBackends]
//      when(backends.normal).thenReturn(components)
//      val action = new AuthAndBackendViaIdapiAction(backends, Return401IfNotSignedInRecently, requiredScopes)
//      val result = action.invokeBlock(request, { _: AuthAndBackendRequest[AnyContent] => Future.successful(Results.Ok) })
//      Await.result(result, Duration.Inf) shouldEqual Results.Ok
//    }

    "use Idapi cookies to build a refined request when an Okta token is not present" in {
      val request = FakeRequest()
      val requiredScopes = List(readSelf)

      val authService = mock[IdentityAuthService]
      when(authService.fetchUserFromOktaToken(request, requiredScopes)).thenReturn(Future.successful(Left(AuthenticationFailure.Unauthorised)))

      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)

      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)

      val action = new AuthAndBackendViaIdapiAction(backends, Return401IfNotSignedInRecently, requiredScopes)
      val result = action.invokeBlock(request, { _: AuthAndBackendRequest[AnyContent] => Future.successful(Results.Ok) })
      Await.result(result, Duration.Inf) shouldEqual Results.Ok
    }
  }
}
