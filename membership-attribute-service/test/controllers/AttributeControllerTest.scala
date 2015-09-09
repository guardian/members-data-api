package controllers

import actions._
import com.gu.identity.play.IdMinimalUser
import models.MembershipAttributes
import org.mockito.Mockito._
import org.specs2.mutable.Specification
import play.api.libs.iteratee.{Input, Iteratee}
import play.api.libs.json.{Json, JsValue}
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.AttributeService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class AttributeControllerTest extends Specification {

  val userId = "123"

  val attributeService = mock(classOf[AttributeService])
  val controller = new AttributeController(attributeService) {
    private def authenticatedUserFor[A](request: RequestHeader): Option[IdMinimalUser] = for {
      scGuU <- request.cookies.get("SC_GU_U")
      guU <- request.cookies.get("GU_U")
    } yield IdMinimalUser(userId, None)

    def unauthenticated(request: RequestHeader): Result = Results.Unauthorized

    def authenticated(onUnauthenticated: RequestHeader => Result = unauthenticated): ActionBuilder[AuthRequest] = {
      import Functions.authenticatedExceptionHandler
      new AuthenticatedBuilder(authenticatedUserFor, onUnauthenticated) andThen authenticatedExceptionHandler
    }

    override val AuthenticatedAction = NoCacheAction andThen authenticated()
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
      val result = controller.getMyAttributes(FakeRequest())
      status(result) shouldEqual UNAUTHORIZED
    }

    "retrieve attributes for user in cookie" in {
      val apiResponse = Future { Some(MembershipAttributes("123", "patron", "abc")) }
      when(attributeService.getAttributes(userId)).thenReturn(apiResponse)

      val guCookie = "gu_cookie"
      val scGuCookie = "sc_gu_cookie"

      val req = FakeRequest().withHeaders("Cookie" -> s"GU_U=$guCookie;SC_GU_U=$scGuCookie")
      val result: Future[Result] = controller.getMyAttributes(req)
      verifySuccessfulResult(result)
    }
  }

  private def addJsonBodyToRequest(req: FakeRequest[_], body: JsValue): FakeRequest[_] =
    req.withJsonBody(body).withHeaders(req.headers.headers :+ (CONTENT_TYPE -> "application/json"): _*)

  private def executeJsonRequest(iteratee: Iteratee[Array[Byte], Result], body: JsValue): Future[Result] =
    iteratee.feed(Input.El(body.toString().getBytes)).flatMap(_.run)
}
