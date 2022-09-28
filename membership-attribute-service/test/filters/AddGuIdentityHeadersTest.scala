package filters

import configuration.Config.testUsernames
import models.AccessClaims
import org.mockito.Mockito.{times, verify, verifyNoInteractions, when}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import play.api.mvc.Results.Ok
import play.api.mvc.{RequestHeader, Result}
import services.IdentityAuthService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AddGuIdentityHeadersTest extends Specification with Mockito {

  val XGuIdentityId = "X-Gu-Identity-Id"
  val XGuMembershipTestUser = "X-Gu-Membership-Test-User"
  val user = AccessClaims(
    primaryEmailAddress = "someUser@email.com",
    id = "testUserId",
    userName = "testUserName",
    hasValidatedEmail = false,
  )

  val identityService = mock[IdentityAuthService]
  val request = mock[RequestHeader]
  when(identityService.user(request)).thenReturn(Future.successful(Some(user)))

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
      val actualResult = AddGuIdentityHeaders.fromUser(resultWithoutIdentityHeaders, user)
      assertHeadersSet(actualResult)
    }

    "add headers for test user " in {
      val testUsername = testUsernames.generate()
      val testUser = user.copy(userName = testUsername)
      val actualResult = AddGuIdentityHeaders.fromUser(resultWithoutIdentityHeaders, testUser)
      assertHeadersSet(actualResult, testUser = true)
    }

    "detect if result has identity headers" in {
      AddGuIdentityHeaders.hasIdentityHeaders(resultWithoutIdentityHeaders) should beEqualTo(false)
      AddGuIdentityHeaders.hasIdentityHeaders(resultWithXGuIdentity) should beEqualTo(false)
      AddGuIdentityHeaders.hasIdentityHeaders(resultWithXGuMembershipTestUser) should beEqualTo(false)
      AddGuIdentityHeaders.hasIdentityHeaders(resultWithAllIdentityHeaders) should beEqualTo(true)
    }

    "detect test users" in {
      val testUsername = testUsernames.generate()
      AddGuIdentityHeaders.isTestUser(Some(testUsername)) should beTrue
    }
    "detect non test users" in {
      AddGuIdentityHeaders.isTestUser(Some("not_a_test_user")) should beFalse
    }
    "consider empty username as non test user" in {
      AddGuIdentityHeaders.isTestUser(None) should beFalse
    }
  }

  "fromIdapiIfMissing should not change the headers or call idapi if the result already has identity headers" in {
    val futureActualResult = AddGuIdentityHeaders.fromIdapiIfMissing(request, resultWithAllIdentityHeaders, identityService)
    val actualResult = Await.result(futureActualResult, 5.seconds)
    verifyNoInteractions(identityService)
    assertHeadersSet(actualResult)
  }

  "fromIdapiIfMissing should call idapi and set the headers if the result doesn't already have identity headers" in {
    val futureActualResult = AddGuIdentityHeaders.fromIdapiIfMissing(request, resultWithoutIdentityHeaders, identityService)
    val actualResult = Await.result(futureActualResult, 5.seconds)
    verify(identityService, times(1)).user(request)
    assertHeadersSet(actualResult)
  }

  "fromIdapiIfMissing should not modify headers if no user is found in idapi when trying to add missing headers" in {
    val notFoundIdentityService = mock[IdentityAuthService]
    when(notFoundIdentityService.user(request)).thenReturn(Future.successful(None))

    val futureActualResult = AddGuIdentityHeaders.fromIdapiIfMissing(request, resultWithoutIdentityHeaders, notFoundIdentityService)
    val actualResult = Await.result(futureActualResult, 5.seconds)
    verify(notFoundIdentityService, times(1)).user(request)
    actualResult.header.headers.size should beEqualTo(1)
    actualResult.header.headers.get("previousHeader") should beSome("previousHeaderValue")
  }

}
