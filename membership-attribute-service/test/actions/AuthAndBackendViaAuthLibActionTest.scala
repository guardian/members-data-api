package actions

import components.{TouchpointBackends, TouchpointComponents}
import models.AccessScope.readSelf
import models.UserFromToken
import org.mockito.Mockito.when
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.mvc.Results.{Forbidden, Unauthorized}
import play.api.mvc.{AnyContent, WrappedRequest}
import play.api.test.FakeRequest
import services.{AuthenticationFailure, IdentityAuthService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class AuthAndBackendViaAuthLibActionTest extends Specification with Mockito {

  "AuthAndBackendViaAuthLibAction.refine" should {

    "give a wrapped request when authorisation is successful" in {
      val request = FakeRequest()
      val requiredScopes = List(readSelf)
      val user = mock[UserFromToken]
      when(user.username).thenReturn(None)
      val authService = mock[IdentityAuthService]
      when(authService.user(requiredScopes)(request)).thenReturn(Future.successful(Right(user)))
      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)
      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)
      val wrappedRequest = new WrappedRequest(request)
      val result = AuthAndBackendViaAuthLibAction.refine[AnyContent, WrappedRequest](backends, requiredScopes, request)((_, _) => wrappedRequest)
      Await.result(result, Duration.Inf) should beRight(wrappedRequest)
    }

    "give an unauthorized result when authentication fails" in {
      val request = FakeRequest()
      val requiredScopes = List(readSelf)
      val user = mock[UserFromToken]
      when(user.username).thenReturn(None)
      val authService = mock[IdentityAuthService]
      when(authService.user(requiredScopes)(request)).thenReturn(Future.successful(Left(AuthenticationFailure.Unauthorised)))
      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)
      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)
      val wrappedRequest = new WrappedRequest(request)
      val result = AuthAndBackendViaAuthLibAction.refine[AnyContent, WrappedRequest](backends, requiredScopes, request)((_, _) => wrappedRequest)
      Await.result(result, Duration.Inf) should beLeft(Unauthorized)
    }

    "give a forbidden result when authorisation fails" in {
      val request = FakeRequest()
      val requiredScopes = List(readSelf)
      val user = mock[UserFromToken]
      when(user.username).thenReturn(None)
      val authService = mock[IdentityAuthService]
      when(authService.user(requiredScopes)(request)).thenReturn(Future.successful(Left(AuthenticationFailure.Forbidden)))
      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)
      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)
      val wrappedRequest = new WrappedRequest(request)
      val result = AuthAndBackendViaAuthLibAction.refine[AnyContent, WrappedRequest](backends, requiredScopes, request)((_, _) => wrappedRequest)
      Await.result(result, Duration.Inf) should beLeft(Forbidden)
    }
  }
}
