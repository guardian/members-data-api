package controllers

import actions.BackendRequest
import akka.actor.ActorSystem
import com.gu.scanamo.error.DynamoReadError
import components.TouchpointComponents
import configuration.Config
import models.Attributes
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.{AttributeService, AuthenticationService}

import scala.concurrent.Future

class AttributeControllerTest extends Specification with AfterAll {
  implicit val as: ActorSystem = ActorSystem("test")

  private val validUserId = "123"
  private val invalidUserId = "456"
  private val attributes = Attributes(
    UserId = validUserId,
    Tier = Some("patron"),
    MembershipNumber = Some("abc"),
    AdFree = Some(false),
    CardExpirationMonth = Some(3),
    CardExpirationYear = Some(2018),
    MembershipJoinDate = Some(new LocalDate(2017, 6, 13))
  )

  private val validUserCookie = Cookie("validUser", "true")
  private val invalidUserCookie = Cookie("invalidUser", "true")

  private val fakeAuthService = new AuthenticationService {
    override def username(implicit request: RequestHeader) = ???
    override def userId(implicit request: RequestHeader) = request.cookies.headOption match {
      case Some(c) if c == validUserCookie => Some(validUserId)
      case Some(c) if c == invalidUserCookie => Some(invalidUserId)
      case _ => None
    }
  }

  // Succeeds for the valid user id
  private object FakeWithBackendAction extends ActionRefiner[Request, BackendRequest] {
    override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = {
      val a = new AttributeService {
        override def set(attributes: Attributes) = ???
        override def get(userId: String) = Future { if (userId == validUserId ) Some(attributes) else None }
        override def delete(userId: String) = ???
        override def getMany(userIds: List[String]): Future[Seq[Attributes]] = ???
        override def update(attributes: Attributes) : Future[Either[DynamoReadError, Attributes]] = ???
      }

      object components extends TouchpointComponents(Config.defaultTouchpointBackendStage) {
        override lazy val attrService = a
      }

      Future(Right(new BackendRequest[A](components, request)))
    }
  }

  private val controller = new AttributeController {
    override lazy val authenticationService = fakeAuthService
    override lazy val backendAction = Action andThen FakeWithBackendAction
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
        |   "adFree": false,
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
      val req = FakeRequest().withCookies(invalidUserCookie)
      val result: Future[Result] = controller.membership(req)

      status(result) shouldEqual NOT_FOUND
    }

    "retrieve attributes for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.membership(req)

      verifySuccessfulResult(result)
    }
  }

  override def afterAll() = as.shutdown()
}
