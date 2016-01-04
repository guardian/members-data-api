package actions

import components.TestTouchpointComponents
import org.specs2.mutable._
import play.api.mvc.Action
import play.api.mvc.Results.Ok
import play.api.test.{WithApplication, FakeRequest}
import play.api.test.Helpers._

import scalaz.syntax.std.option._

class WithBackendFromSalesforceActionTest extends Specification {
  "A SalesforceAuthAction" should {
    def fakeRequest(secretParam: Option[String]) = {
      val uri = secretParam.fold("/")(s => s"/?secret=$s")
      FakeRequest(POST, uri).withTextBody("body")
    }

    val orgIdAction = (Action andThen BackendFromSalesforceAction) {
      request => Ok(request.touchpoint.sfOrganisationId)
    }

    "selects the backend that matches the secret in the query string" in new WithApplication {
      val result = call(orgIdAction, fakeRequest(TestTouchpointComponents.sfSecret.some))

      status(result) mustEqual OK
      contentAsString(result) mustEqual TestTouchpointComponents.sfOrganisationId
    }

    "block a request" should {
      "with no secret query parameter" in {
        val result = call(orgIdAction, fakeRequest(None))
        status(result) mustEqual UNAUTHORIZED
      }

      "with a wrong secret" in {
        val result = call(orgIdAction, fakeRequest(Some("wrong secret")))
        status(result) mustEqual UNAUTHORIZED
      }
    }
  }
}