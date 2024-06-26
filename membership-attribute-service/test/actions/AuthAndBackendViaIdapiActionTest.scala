package actions

import com.gu.identity.auth.OktaUserCredentials
import components.{TouchpointBackends, TouchpointComponents}
import filters.TestUserChecker
import models.AccessScope.readSelf
import models.{ApiErrors, UserFromToken}
import org.mockito.IdiomaticMockito
import org.mockito.Mockito.when
import org.specs2.mutable.Specification
import play.api.mvc.{AnyContent, Result, Results}
import play.api.test.FakeRequest
import services.{IdentityAuthService, UserAndCredentials}

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class AuthAndBackendViaIdapiActionTest extends Specification with IdiomaticMockito {

  "refine" should {

    val zoneId = ZoneId.of("UTC")
    val requiredScopes = List(readSelf)

    def oktaTest(
        authenticatedTime: ZonedDateTime,
        howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn,
    ): Future[Result] = {
      val request = FakeRequest()

      val user = mock[UserFromToken]
      when(user.username).thenReturn(None)
      when(user.authTime).thenReturn(Some(authenticatedTime))

      val isTestUser = mock[TestUserChecker]

      val authService = mock[IdentityAuthService]
      when(authService.userAndCredentials(request, requiredScopes))
        .thenReturn(Future.successful(Right(UserAndCredentials(user, OktaUserCredentials("token")))))

      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)

      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)

      val action = new AuthAndBackendViaIdapiAction(backends, howToHandleRecencyOfSignedIn, isTestUser, requiredScopes)
      action.invokeBlock(request, { _: AuthAndBackendRequest[AnyContent] => Future.successful(Results.Ok) })
    }

    "use Okta token to build a refined request when a recently authenticated Okta token is present" in {
      val authenticatedTime = ZonedDateTime.of(2023, 1, 18, 9, 20, 0, 1, zoneId)
      val result = oktaTest(authenticatedTime, Return401IfNotSignedInRecently)
      Await.result(result, Duration.Inf) shouldEqual Results.Ok
    }

    "use Okta token to build a refined request when a stale Okta token is present and howToHandleRecencyOfSignedIn is ContinueRegardlessOfSignInRecency" in {
      val authenticatedTime = ZonedDateTime.of(2023, 1, 18, 7, 42, 0, 0, zoneId)
      val result = oktaTest(authenticatedTime, ContinueRegardlessOfSignInRecency)
      Await.result(result, Duration.Inf) shouldEqual Results.Ok
    }

    "fail with a 401 when call to identityAuthService fails with a 401" in {
      val request = FakeRequest()

      val isTestUser = mock[TestUserChecker]

      val authService = mock[IdentityAuthService]
      when(authService.userAndCredentials(request, requiredScopes))
        .thenReturn(Future.successful(Left(ApiErrors.unauthorized)))

      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)

      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)

      val action = new AuthAndBackendViaIdapiAction(backends, Return401IfNotSignedInRecently, isTestUser, requiredScopes)
      val result = action.invokeBlock(request, { _: AuthAndBackendRequest[AnyContent] => Future.successful(Results.Ok) })
      Await.result(result, Duration.Inf).toString shouldEqual Results.Unauthorized
        .withHeaders("Pragma" -> "no-cache", "Cache-Control" -> "no-cache, private")
        .toString
    }

    "fail with a 403 when call to identityAuthService fails with a 403" in {
      val request = FakeRequest()

      val isTestUser = mock[TestUserChecker]

      val authService = mock[IdentityAuthService]
      when(authService.userAndCredentials(request, requiredScopes))
        .thenReturn(Future.successful(Left(ApiErrors.forbidden)))

      val components = mock[TouchpointComponents]
      when(components.identityAuthService).thenReturn(authService)

      val backends = mock[TouchpointBackends]
      when(backends.normal).thenReturn(components)

      val action = new AuthAndBackendViaIdapiAction(backends, Return401IfNotSignedInRecently, isTestUser, requiredScopes)
      val result = action.invokeBlock(request, { _: AuthAndBackendRequest[AnyContent] => Future.successful(Results.Ok) })
      Await.result(result, Duration.Inf).toString shouldEqual Results.Forbidden
        .withHeaders("Pragma" -> "no-cache", "Cache-Control" -> "no-cache, private")
        .toString
    }
  }
}
