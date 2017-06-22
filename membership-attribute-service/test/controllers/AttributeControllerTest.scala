package controllers

import actions.BackendRequest
import akka.actor.ActorSystem
import com.gu.scanamo.error.DynamoReadError
import components.TouchpointComponents
import configuration.Config
import models.{Attributes, CardDetails, Wallet}
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
    Wallet = Some(Wallet(
      recurringContributionCard = Some(CardDetails("4321", 6, 2018, "contribution")),
      membershipCard = Some(CardDetails("1234", 5, 2017, "membership"))
    )),
    MembershipJoinDate = Some(new LocalDate(2017, 5, 13)),
    RecurringContributionPaymentPlan = Some("Monthly Contribution")
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
                   |   "membershipJoinDate": "2017-05-13",
                   |   "cardHasExpiredForProduct": "membership",
                   |   "cardExpiredOn": "2017-05-31"
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
                   |   "wallet": {
                   |     "membershipCard": {
                   |       "last4": "1234",
                   |       "expirationMonth": 5,
                   |       "expirationYear": 2017,
                   |       "forProduct": "membership"
                   |     },
                   |     "recurringContributionCard": {
                   |       "last4": "4321",
                   |       "expirationMonth": 6,
                   |       "expirationYear": 2018,
                   |       "forProduct": "contribution"
                   |     }
                   |   },
                   |   "adFree": false,
                   |   "membershipJoinDate": "2017-05-13",
                   |   "contributionPaymentPlan":"Monthly Contribution",
                   |   "contentAccess": {
                   |     "member": true,
                   |     "paidMember": true,
                   |     "recurringContributor": true
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

    "return not found for unknown users in membership and attributes" in {
      val req = FakeRequest().withCookies(invalidUserCookie)
      val result1 = controller.membership(req)
      val result2 = controller.attributes(req)

      status(result1) shouldEqual NOT_FOUND
      status(result2) shouldEqual NOT_FOUND
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

  override def afterAll() = as.shutdown()
}
