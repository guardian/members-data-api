package controllers

import actions.{AuthAndBackendRequest, BackendRequest, CommonActions, HowToHandleRecencyOfSignedIn}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.gu.identity.{RedirectAdviceResponse, SignedInRecently}
import components.{TouchpointBackends, TouchpointComponents}
import configuration.Config
import models.{Attributes, ContributionData}
import org.joda.time.LocalDate
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import scalaz.\/
import services.OneOffContributionDatabaseService.DatabaseGetResult
import services.{AttributesFromZuora, AuthenticationService, OneOffContributionDatabaseService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AttributeControllerTest extends Specification with AfterAll with Mockito {

  implicit val as: ActorSystem = ActorSystem("test")

  private val validUserId = "123"
  private val invalidUserId = "456"
  private val testAttributes = Attributes(
    UserId = validUserId,
    Tier = Some("patron"),
    MembershipJoinDate = Some(new LocalDate(2017, 5, 13)),
    RecurringContributionPaymentPlan = Some("Monthly Contribution"),
    DigitalSubscriptionExpiryDate = Some(new LocalDate(2100, 1, 1)),
    PaperSubscriptionExpiryDate = Some(new LocalDate(2099, 1, 1)),
    GuardianWeeklySubscriptionExpiryDate = Some(new LocalDate(2099, 1, 1))
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

  private object FakeAuthAndBackendViaIdapiAction extends ActionRefiner[Request, AuthAndBackendRequest] {
    override val executionContext = scala.concurrent.ExecutionContext.global
    override protected def refine[A](request: Request[A]): Future[Either[Result, AuthAndBackendRequest[A]]] = {

      object components extends TouchpointComponents(Config.defaultTouchpointBackendStage)

      val redirectAdviceResponse = RedirectAdviceResponse(SignedInRecently,None,None,None,None)

      Future(Right(new AuthAndBackendRequest[A](redirectAdviceResponse, components, request)))
    }
  }

  private val actorSystem = ActorSystem()
  private val touchpointBackends = new TouchpointBackends(actorSystem)
  private val stubParser = Helpers.stubBodyParser(AnyContent("test"))
  private val ex = scala.concurrent.ExecutionContext.global
  private val commonActions = new CommonActions(touchpointBackends, stubParser)(scala.concurrent.ExecutionContext.global, ActorMaterializer()) {
    override val BackendFromCookieAction = NoCacheAction andThen FakeWithBackendAction
    override def AuthAndBackendViaIdapiAction(howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn)= NoCacheAction andThen FakeAuthAndBackendViaIdapiAction
  }

  object FakePostgresService extends OneOffContributionDatabaseService {
    def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]] =
      Future.successful(\/.right(Nil))

    def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]] =
      Future.successful(\/.right(None))
  }

  private val controller = new AttributeController(new AttributesFromZuora(), commonActions, Helpers.stubControllerComponents(), FakePostgresService) {
    override lazy val authenticationService = fakeAuthService
    override val executionContext = scala.concurrent.ExecutionContext.global
    override def pickAttributes(identityId: String)(implicit request: AuthAndBackendRequest[AnyContent]): Future[(String, Option[Attributes])] = Future {
      if (identityId == validUserId ) ("Zuora", Some(testAttributes)) else ("Zuora", None)
    }
  }

  private def verifyDefaultFeaturesResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""
                   | {
                   |   "adblockMessage": true
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
        |   "userId": "123",
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
                   |   "userId": "123",
                   |   "membershipJoinDate": "2017-05-13",
                   |   "recurringContributionPaymentPlan":"Monthly Contribution",
                   |   "digitalSubscriptionExpiryDate":"2100-01-01",
                   |   "paperSubscriptionExpiryDate":"2099-01-01",
                   |   "guardianWeeklyExpiryDate":"2099-01-01",
                   |   "showSupportMessaging": false,
                   |   "contentAccess": {
                   |     "member": true,
                   |     "paidMember": true,
                   |     "recurringContributor": true,
                   |     "digitalPack": true,
                   |     "paperSubscriber": true,
                   |     "guardianWeeklySubscriber": true
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
                     |  "showSupportMessaging": true,
                     |  "contentAccess": {
                     |    "member": false,
                     |    "paidMember": false,
                     |    "recurringContributor": false,
                     |    "digitalPack": false,
                     |    "paperSubscriber": false,
                     |    "guardianWeeklySubscriber": false
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
