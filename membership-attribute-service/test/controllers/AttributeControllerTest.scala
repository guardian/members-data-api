package controllers

import models.MembershipAttributes
import org.mockito.Mockito._
import org.specs2.mutable.Specification
import play.api.libs.iteratee.{Input, Iteratee}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.{AttributeService, AuthenticationService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AttributeControllerTest extends Specification {

  val userId = "123"

  val attributeService = mock(classOf[AttributeService])
  val authService = mock(classOf[AuthenticationService])

  val controller = new AttributeController(attributeService) {
    override lazy val authenticationService = authService
  }

  def verifySuccessfulResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
        | {
        |   "tier": "patron",
        |   "membershipNumber": "abc"
        | }
      """.stripMargin)
  }

  "getMyAttributes" should {
    "return unauthorised when cookies not provided" in {
      val req = FakeRequest()
      when(authService.userId(req)).thenReturn(None)
      val result = controller.getMyAttributes(req)

      status(result) shouldEqual UNAUTHORIZED
    }

    "retrieve attributes for user in cookie" in {
      val apiResponse = Future { Some(MembershipAttributes("123", "patron", Some("abc"))) }
      when(attributeService.getAttributes(userId)).thenReturn(apiResponse)

      val req = FakeRequest()
      when(authService.userId(req)).thenReturn(Some(userId))

      val result: Future[Result] = controller.getMyAttributes(req)
      verifySuccessfulResult(result)
    }
  }

  private def addJsonBodyToRequest(req: FakeRequest[_], body: JsValue): FakeRequest[_] =
    req.withJsonBody(body).withHeaders(req.headers.headers :+ (CONTENT_TYPE -> "application/json"): _*)

  private def executeJsonRequest(iteratee: Iteratee[Array[Byte], Result], body: JsValue): Future[Result] =
    iteratee.feed(Input.El(body.toString().getBytes)).flatMap(_.run)
}
