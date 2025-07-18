package filters

import com.typesafe.config.ConfigFactory
import configuration.CreateTestUsernames
import models.UserFromToken
import org.mockito.IdiomaticMockito
import org.mockito.Mockito.{times, verify, verifyNoInteractions, when}
import org.specs2.mutable.Specification
import play.api.mvc.Results.Ok
import play.api.mvc.{RequestHeader, Result}
import services.AuthenticationFailure.Unauthorised
import services.IdentityAuthService
import testdata.TestLogPrefix.testLogPrefix

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AddGuIdentityHeadersTest extends Specification with IdiomaticMockito {

  val XGuIdentityId = "X-Gu-Identity-Id"
  val XGuMembershipTestUser = "X-Gu-Membership-Test-User"
  val user = UserFromToken(
    primaryEmailAddress = "someUser@email.com",
    identityId = "testUserId",
    username = Some("testUserName"),
    userEmailValidated = None,
    authTime = None,
    oktaId = "testOktaId",
  )

  val request = mock[RequestHeader]
  val config = ConfigFactory.load()
  val testUsernames = CreateTestUsernames.from(config)
  val testUserChecker = new TestUserChecker(testUsernames)

  def setup = {
    val identityService = mock[IdentityAuthService]
    when(identityService.user(requiredScopes = Nil)(request)).thenReturn(Future.successful(Right(user)))
    val addGuIdentityHeaders = new AddGuIdentityHeaders(identityService, testUserChecker)
    (identityService, addGuIdentityHeaders)
  }

  val resultWithoutIdentityHeaders = Ok("testResult").withHeaders("previousHeader" -> "previousHeaderValue")
  val resultWithXGuIdentity = resultWithoutIdentityHeaders.withHeaders(XGuIdentityId -> "testUserId")
  val resultWithXGuMembershipTestUser = resultWithoutIdentityHeaders.withHeaders(XGuMembershipTestUser -> "false")
  val resultWithAllIdentityHeaders = resultWithXGuIdentity.withHeaders(XGuMembershipTestUser -> "false")

  def assertHeadersSet(actualResult: Result, testUser: Boolean = false) = {
    val actualHeaders = actualResult.header.headers
    actualHeaders.get("previousHeader") should beSome("previousHeaderValue")
    actualHeaders.get(XGuIdentityId) should beSome("testUserId")
    actualHeaders.get(XGuMembershipTestUser) should beSome(testUser.toString)
    actualHeaders.size should beEqualTo(3)
  }

  "AddGuIdentityHeaders" should {

    "add headers for user " in {
      val (_, addGuIdentityHeaders) = setup
      val actualResult = addGuIdentityHeaders.fromUser(resultWithoutIdentityHeaders, user)
      assertHeadersSet(actualResult)
    }

    "add headers for test user " in {
      val (_, addGuIdentityHeaders) = setup
      val testUsername = testUsernames.generateEmail(Some("test.user@thegulocal.com"))
      val testUser = user.copy(primaryEmailAddress = testUsername.email)
      val actualResult = addGuIdentityHeaders.fromUser(resultWithoutIdentityHeaders, testUser)
      assertHeadersSet(actualResult, testUser = true)
    }

    "detect if result has identity headers" in {
      val (_, addGuIdentityHeaders) = setup
      addGuIdentityHeaders.hasIdentityHeaders(resultWithoutIdentityHeaders) should beEqualTo(false)
      addGuIdentityHeaders.hasIdentityHeaders(resultWithXGuIdentity) should beEqualTo(false)
      addGuIdentityHeaders.hasIdentityHeaders(resultWithXGuMembershipTestUser) should beEqualTo(false)
      addGuIdentityHeaders.hasIdentityHeaders(resultWithAllIdentityHeaders) should beEqualTo(true)
    }

    "detect test users" in {
      val testUsername = testUsernames.generateEmail(Some("test.user@thegulocal.com"))
      val isTestUser = new TestUserChecker(testUsernames)
      isTestUser.isTestUser(testUsername.email) should beTrue
    }
    "detect non test users" in {
      testUserChecker.isTestUser("not_a_test_user@thegulocal.com") should beFalse
    }
    "detect non test users with subaddresses" in {
      testUserChecker.isTestUser("not_a_test_user+blahblah@thegulocal.com") should beFalse
    }
    "not choke on an empty email address" in {
      testUserChecker.isTestUser("") should beFalse
    }
  }

  "fromIdapiIfMissing should not change the headers or call idapi if the result already has identity headers" in {
    val (identityService, addGuIdentityHeaders) = setup
    val futureActualResult = addGuIdentityHeaders.fromIdapiIfMissing(request, resultWithAllIdentityHeaders)
    val actualResult = Await.result(futureActualResult, 5.seconds)
    verifyNoInteractions(identityService)
    assertHeadersSet(actualResult)
  }

  "fromIdapiIfMissing should call idapi and set the headers if the result doesn't already have identity headers" in {
    val (identityService, addGuIdentityHeaders) = setup
    val futureActualResult = addGuIdentityHeaders.fromIdapiIfMissing(request, resultWithoutIdentityHeaders)
    val actualResult = Await.result(futureActualResult, 5.seconds)
    verify(identityService, times(1)).user(requiredScopes = Nil)(request)
    assertHeadersSet(actualResult)
  }

  "fromIdapiIfMissing should not modify headers if no user is found in idapi when trying to add missing headers" in {
    val notFoundIdentityService = mock[IdentityAuthService]
    when(notFoundIdentityService.user(requiredScopes = Nil)(request)).thenReturn(Future.successful(Left(Unauthorised)))

    val guIdentityHeaders = new AddGuIdentityHeaders(notFoundIdentityService, testUserChecker)
    val futureActualResult = guIdentityHeaders.fromIdapiIfMissing(request, resultWithoutIdentityHeaders)
    val actualResult = Await.result(futureActualResult, 5.seconds)
    verify(notFoundIdentityService, times(1)).user(requiredScopes = Nil)(request)
    actualResult.header.headers.size should beEqualTo(1)
    actualResult.header.headers.get("previousHeader") should beSome("previousHeaderValue")
  }

}
