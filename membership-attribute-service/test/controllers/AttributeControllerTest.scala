package controllers

import com.gu.config.{LegacyMembership, Membership, DigitalPack, ProductFamily}
import mocks.{AuthenticationServiceFake, AttributeServiceFake, ContactRepositoryDummy, PaymentServiceStub}
import models.Attributes
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class AttributeControllerTest extends Specification {

  val authService = new AuthenticationServiceFake
  val attributes = Seq(Attributes(authService.validUserId, "patron", Some("abc")))
  val attrService = new AttributeServiceFake(attributes)
  val contactRepo = new ContactRepositoryDummy
  val paymentService = new PaymentServiceStub

  val digipack = new DigitalPack("1", "2", "3")
  val membership = new Membership("1","1","1","1","1","1","1","1", new LegacyMembership("1","1","1","1","1","1","1"))
  val controller = new AttributeController(paymentService,contactRepo, attrService, membership, digipack) {
    override lazy val authenticationService = authService
  }

  def verifySuccessfulResult(result: Future[Result]) = {

    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
        | {
        |   "tier": "patron",
        |   "membershipNumber": "abc",
        |   "userId": "123",
        |   "contentAccess":{"member":true,"paidMember":true}
        | }
      """.stripMargin)
  }

  "getMyAttributes" should {
    "return unauthorised when cookies not provided" in {
      val req = FakeRequest()
      val result = controller.membership(req)

      status(result) shouldEqual UNAUTHORIZED
    }

    "return not found for unknown users" in {
      val req = FakeRequest().withCookies(authService.invalidUserCookie)
      val result: Future[Result] = controller.membership(req)
      status(result) shouldEqual NOT_FOUND
    }

    "retrieve attributes for user in cookie" in {
      val req = FakeRequest().withCookies(authService.validUserCookie)
      val result: Future[Result] = controller.membership(req)

      verifySuccessfulResult(result)
    }
  }
}
