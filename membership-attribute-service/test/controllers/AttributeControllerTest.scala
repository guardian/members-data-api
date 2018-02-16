package controllers

import actions.{BackendRequest, CommonActions}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import components.{TouchpointBackends, TouchpointComponents}
import configuration.Config
import models.Attributes
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import services.AuthenticationService

import scala.concurrent.Future

class AttributeControllerTest extends Specification with AfterAll {

  implicit val as: ActorSystem = ActorSystem("test")

  private val validUserId = "123"
  private val invalidUserId = "456"
  private val testAttributes = Attributes(
    UserId = validUserId,
    Tier = Some("patron"),
    MembershipNumber = Some("abc"),
    AdFree = Some(false),
    MembershipJoinDate = Some(new LocalDate(2017, 5, 13)),
    RecurringContributionPaymentPlan = Some("Monthly Contribution"),
    DigitalSubscriptionExpiryDate = Some(new LocalDate(2100, 1, 1))
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
    override val executionContext = scala.concurrent.ExecutionContext.global
    override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = {

      object components extends TouchpointComponents(Config.defaultTouchpointBackendStage)

      Future(Right(new BackendRequest[A](components, request)))
    }
  }

  private val actorSystem = ActorSystem()
  private val touchpointBackends = new TouchpointBackends(actorSystem)
  private val stubParser = Helpers.stubBodyParser(AnyContent("test"))
  private val commonActions = new CommonActions(touchpointBackends, stubParser)(scala.concurrent.ExecutionContext.global, ActorMaterializer())
  private val controller = new AttributeController(commonActions) {

    override lazy val authenticationService = fakeAuthService
    override lazy val backendAction = Action andThen FakeWithBackendAction
    override def pickAttributes(identityId: String)(implicit request: BackendRequest[AnyContent]): Future[(String, Option[Attributes])] = Future {
      if (identityId == validUserId ) ("Zuora", Some(testAttributes)) else ("Zuora", None)
    }
  }

  private def verifyDefaultFeaturesResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
                   | {
                   |   "adblockMessage": true,
                   |   "adFree": false
                   | }
                 """.stripMargin)
  }

  private def verifySuccessfulFeaturesResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
                   | {
                   |   "userId": "123",
                   |   "adblockMessage": false,
                   |   "adFree": false,
                   |   "membershipJoinDate": "2017-05-13"
                   | }
                 """.stripMargin)
  }

  private def verifySuccessfulMembershipResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
        | {
        |   "tier": "patron",
        |   "membershipNumber": "abc",
        |   "userId": "123",
        |   "adFree": false,
        |   "contentAccess": {
        |     "member": true,
        |     "paidMember": true
        |   }
        | }
      """.stripMargin)
  }

  private def verifySuccessfullAttributesResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
                   | {
                   |   "tier": "patron",
                   |   "membershipNumber": "abc",
                   |   "userId": "123",
                   |   "adFree": false,
                   |   "membershipJoinDate": "2017-05-13",
                   |   "recurringContributionPaymentPlan":"Monthly Contribution",
                   |   "digitalSubscriptionExpiryDate":"2100-01-01",
                   |   "contentAccess": {
                   |     "member": true,
                   |     "paidMember": true,
                   |     "recurringContributor": true,
                   |     "digitalPack": true
                   |   }
                   | }
                 """.stripMargin)
  }

  "getMyMembershipAttributesFeatures" should {
    "return unauthorised when cookies not provided" in {
      val req = FakeRequest()
      val result1 = controller.membership(req)
      val result2 = controller.attributes(req)
      val result3 = controller.features(req)

      status(result1) shouldEqual UNAUTHORIZED
      status(result2) shouldEqual UNAUTHORIZED
      status(result3) shouldEqual UNAUTHORIZED
    }

    "return not found for unknown users in membership" in {
      val req = FakeRequest().withCookies(invalidUserCookie)
      val result = controller.membership(req)

      status(result) shouldEqual NOT_FOUND
    }

    "return all false attributes for unknown users" in {
      val req = FakeRequest().withCookies(invalidUserCookie)
      val result = controller.attributes(req)

      status(result) shouldEqual OK
      val jsonBody = contentAsJson(result)
      jsonBody shouldEqual
        Json.parse("""
                     |{
                     |  "userId": "456",
                     |  "adFree": false,
                     |  "contentAccess": {
                     |    "member": false,
                     |    "paidMember": false,
                     |    "recurringContributor": false,
                     |    "digitalPack": false
                     |  }
                     |}""".stripMargin)
    }

    "retrieve default features for unknown users" in {
      val req = FakeRequest().withCookies(invalidUserCookie)
      val result = controller.features(req)

      verifyDefaultFeaturesResult(result)
    }

    "retrieve features for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.features(req)

      verifySuccessfulFeaturesResult(result)
    }

    "retrieve membership attributes for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.membership(req)

      verifySuccessfulMembershipResult(result)
    }

    "retrieve all the attributes for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.attributes(req)

      verifySuccessfullAttributesResult(result)
    }

  }

  override def afterAll() = as.terminate()
}
