package services

import com.squareup.okhttp.ResponseBody
import org.specs2.mutable.Specification
import IdentityService._

class IdentityServiceTest extends Specification {

  "Identity service" should {

    val mockState = new IdentityConfig {
      override def token: String = "token"
      override def url: String = "http://example.com"
    }

    "Correctly build an authenticated request" in {
      val req = IdentityService.authRequest(mockState).url(mockState.url)
      req.build().header("Authorization") mustEqual "Bearer token"
    }

    "Correctly build a request to get a user by email address" in {
      val req = IdentityService.userRequest(mockState)("foo@bar.com")
      req.build().url.getQuery mustEqual "emailAddress=foo%40bar.com"
    }

    "Correctly build a request to set a user's marketing prefs" in {
      val req = IdentityService.marketingRequest(mockState)(IdentityId("123"), prefs = false)
      req.build().body().contentType() mustEqual IdentityService.json
      req.build().url.toString mustEqual s"${mockState.url}/user/123"
    }

    "Parse a user response correctly" in {
      val r = ResponseBody.create(IdentityService.json,"""{"status":"ok","user":{"id":"12345","dates":{"accountCreatedDate":"2016-04-07T10:29:13Z","lastActivityDate":"2016-04-07T10:37:11Z"},"primaryEmailAddress":"test@example.com","publicFields":{"username":"test","displayName":"test","vanityUrl":"test","usernameLowerCase":"test"},"statusFields":{"userEmailValidated":false}}}""")
      IdentityService.parseUserResponse(r) mustEqual Some(IdentityId("12345"))
    }

    "Parse an update response correctly" in {
      val r = ResponseBody.create(IdentityService.json,"""{"status":"ok","user":{"id":"12345","statusFields":{"receiveGnmMarketing":false}}}""")
      IdentityService.parseMarketingResponse(r) mustEqual true
    }
  }
}
