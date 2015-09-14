package actions

import configuration.Config
import org.specs2.mutable._
import play.api.mvc.Results.Ok
import play.api.test.FakeRequest
import play.api.test.Helpers._

class SalesforceAuthActionTest extends Specification {
  "A SalesforceAuthAction" should {
    val action = SalesforceAuthAction { Ok }
    def fakeRequest(secretParam: Option[String]) = {
      val uri = secretParam.fold("/")(s => s"/?secret=$s")
      FakeRequest(POST, uri).withTextBody("body")
    }

    "let through a request with the Salesforce shared secret in the query string" in {
      val result = call(action, fakeRequest(Some(Config.salesforceSecret)))
      status(result) mustEqual OK
    }

    "block a request" should {
      "with no secret query parameter" in {
        val result = call(action, fakeRequest(None))
        status(result) mustEqual UNAUTHORIZED
      }

      "with a wrong secret" in {
        val result = call(action, fakeRequest(Some("wrong secret")))
        status(result) mustEqual UNAUTHORIZED
      }
    }
  }
}