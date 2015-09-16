package actions

import org.specs2.mutable._
import play.api.mvc.Action
import services._
import play.api.mvc.Results.Ok
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.mvc.BodyParsers.parse

class SalesforceSignedActionTest extends Specification {
  private val rightSignature = "right signature"
  private val wrongFormatSignature = "wrong format"

  implicit val fakeSignatureChecker = new SalesforceSignatureChecker {
    override def check(payload: String)(signature: String): SalesforceSignatureCheck =
      if (signature == rightSignature) CheckSuccessful
      else if (signature == wrongFormatSignature) FormatError
      else WrongSignature
  }

  val action: Action[String] = Action(parse.tolerantText) { _ => Ok }
  val sigHeader = "X-SALESFORCE-SIGNATURE"

  "it" should {
    "lets the request through if the signature is valid" in {
      val req = FakeRequest().withHeaders(sigHeader -> rightSignature)
      val result = call(SalesforceSignedAction(action), req)

      status(result) shouldEqual OK
    }

    "not authorise the request if the signature is invalid" in {
      val req = FakeRequest().withHeaders(sigHeader -> "wrong signature")
      val result = call(SalesforceSignedAction(action), req)

      status(result) shouldEqual UNAUTHORIZED
    }

    "not authorise the request if the signature is absent" in {
      val result = call(SalesforceSignedAction(action), FakeRequest())

      status(result) shouldEqual UNAUTHORIZED
    }

    "block the request with a malformed signature" in {
      val req = FakeRequest().withHeaders(sigHeader -> wrongFormatSignature)
      val result = call(SalesforceSignedAction(action), req)

      status(result) shouldEqual BAD_REQUEST
    }
  }

}