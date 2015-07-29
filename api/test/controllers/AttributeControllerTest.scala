package controllers

import actions._
import com.gu.identity.play.IdMinimalUser
import models.{ApiResponse, MembershipAttributes}
import org.joda.time.LocalDate
import org.mockito.Mockito._
import org.specs2.mutable.Specification
import play.api.mvc.Security.AuthenticatedBuilder
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.AttributeService

import scala.concurrent.Future

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
    (jsonBody \ "response" \ "tier").toOption.map(_.as[String]) shouldEqual Some("patron")
    (jsonBody \ "response" \ "membershipNumber").toOption.map(_.as[String]) shouldEqual Some("abc")
  }


  "getAttributes" should {
    "retrieve attributes for the given user id" in {
      val apiResponse = ApiResponse.Right(MembershipAttributes(userId, LocalDate.now, "patron", "abc"))
      when(attributeService.getAttributes(userId)).thenReturn(apiResponse)

      val result = controller.getAttributes(userId)(FakeRequest())
      verifySuccessfulResult(result)
    }
  }

  "getMyAttributes" should {
    "return unauthorised when cookies not provided" in {
      val result = controller.getMyAttributes(FakeRequest())
      status(result) shouldEqual UNAUTHORIZED
    }

    "retrieve attributes for user in cookie" in {
      val apiResponse = ApiResponse.Right(MembershipAttributes(userId, LocalDate.now, "patron", "abc"))
      when(attributeService.getAttributes(userId)).thenReturn(apiResponse)

      val guCookie = "gu_cookie"
      val scGuCookie = "sc_gu_cookie"

      val req = FakeRequest().withHeaders("Cookie" -> s"GU_U=$guCookie;SC_GU_U=$scGuCookie")
      val result = controller.getMyAttributes(req)
      verifySuccessfulResult(result)
    }
  }
}
