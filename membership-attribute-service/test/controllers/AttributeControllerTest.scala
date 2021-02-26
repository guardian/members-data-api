package controllers

import actions.{AuthAndBackendRequest, AuthenticatedUserAndBackendRequest, CommonActions, HowToHandleRecencyOfSignedIn}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.gu.identity.model.{StatusFields, User}
import com.gu.identity.{RedirectAdviceResponse, SignedInRecently}
import components.{TouchpointBackends, TouchpointComponents}
import configuration.Config
import models.{Attributes, ContributionData, MobileSubscriptionStatus}
import org.joda.time.LocalDate
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import scalaz.\/
import services.{AttributesFromZuora, AuthenticationService, MobileSubscriptionService, FakePostgresService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import models.SupportReminders
import models.RecurringReminderStatus

class AttributeControllerTest extends Specification with AfterAll with Mockito {

  implicit val as: ActorSystem = ActorSystem("test")

  private val validUserId = "123"
  private val userWithoutAttributesUserId = "456"
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
  private val userWithoutAttributesCookie = Cookie("invalidUser", "true")
  private val validUser = User(
    primaryEmailAddress = "test@gu.com",
    id = validUserId,
  )

  private val userWithoutAttributes = User(
    primaryEmailAddress = "notcached@gu.com",
    id = userWithoutAttributesUserId
  )

  private val guardianEmployeeUser = User(
    primaryEmailAddress = "foo@guardian.co.uk",
    id = "1234321",
    statusFields = StatusFields(userEmailValidated = Some(true))
  )
  private val guardianEmployeeCookie = Cookie("employeeDigiPackHack", "true")

  private val guardianEmployeeUserTheguardian = User(
    primaryEmailAddress = "foo@theguardian.com",
    id = "123theguardiancom",
    statusFields = StatusFields(userEmailValidated = Some(true))
  )
  private val guardianEmployeeCookieTheguardian = Cookie("employeeDigiPackHackTheguardian", "true")


  private val validEmployeeUser = User(
    primaryEmailAddress = "bar@theguardian.com",
    id = "userWithRealProducts",
    statusFields = StatusFields(userEmailValidated = Some(true))
  )
  private val validEmployeeUserCookie = Cookie("userWithRealProducts", "true")

  private val fakeAuthService = new AuthenticationService {
    override def user(implicit request: RequestHeader) =
      request.cookies.headOption match {
        case Some(c) if c == validUserCookie => Future.successful(Some(validUser))
        case Some(c) if c == userWithoutAttributesCookie => Future.successful(Some(userWithoutAttributes))
        case Some(c) if c == guardianEmployeeCookie => Future.successful(Some(guardianEmployeeUser))
        case Some(c) if c == guardianEmployeeCookieTheguardian => Future.successful(Some(guardianEmployeeUserTheguardian))
        case Some(c) if c == validEmployeeUserCookie => Future.successful(Some(validEmployeeUser))
        case _ => Future.successful(None)
      }
  }

  private object FakeAuthAndBackendViaAuthLibAction extends ActionRefiner[Request, AuthenticatedUserAndBackendRequest] {
    override val executionContext = scala.concurrent.ExecutionContext.global
    override protected def refine[A](request: Request[A]): Future[Either[Result, AuthenticatedUserAndBackendRequest[A]]] = {

      object components extends TouchpointComponents(Config.defaultTouchpointBackendStage)

      fakeAuthService.user(request) map { user: Option[User] =>
        Right(new AuthenticatedUserAndBackendRequest[A](user, components, request))
      }
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
    override val AuthAndBackendViaAuthLibAction = NoCacheAction andThen FakeAuthAndBackendViaAuthLibAction
    override def AuthAndBackendViaIdapiAction(howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn)= NoCacheAction andThen FakeAuthAndBackendViaIdapiAction
  }

  object FakeMobileSubscriptionService extends MobileSubscriptionService {
    override def getSubscriptionStatusForUser(identityId: String): Future[String \/ Option[MobileSubscriptionStatus]] =
      Future.successful(\/.right(None))
  }

  private val controller = new AttributeController(new AttributesFromZuora(), commonActions, Helpers.stubControllerComponents(), FakePostgresService, FakeMobileSubscriptionService) {
    override val executionContext = scala.concurrent.ExecutionContext.global
    override def getAttributesWithConcurrencyLimitHandling(identityId: String)(implicit request: AuthenticatedUserAndBackendRequest[AnyContent]): Future[(String, Option[Attributes])] = Future {
      if (identityId == validUserId || identityId == validEmployeeUser.id)
        ("Zuora", Some(testAttributes))
      else
        ("Zuora", None)
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
      val req = FakeRequest().withCookies(userWithoutAttributesCookie)
      val result = controller.membership(req)

      status(result) shouldEqual NOT_FOUND
    }

    "return all false attributes for unknown users" in {
      val req = FakeRequest().withCookies(userWithoutAttributesCookie)
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
      val req = FakeRequest().withCookies(userWithoutAttributesCookie)
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

    val digipackAllowEmployeeAccessDateHack = Some(new LocalDate(2999, 1, 1))
    "allow DigiPack access via hack to guardian employees with validated guardian.co.uk email" in {
      val req = FakeRequest().withCookies(guardianEmployeeCookie)
      val defaultAttribsWithDigipackOverride =
        Attributes(guardianEmployeeUser.id)
          .copy(DigitalSubscriptionExpiryDate = digipackAllowEmployeeAccessDateHack)
      contentAsJson(controller.attributes(req)) shouldEqual Json.toJson(defaultAttribsWithDigipackOverride)
    }

    "allow DigiPack access via hack to guardian employees with validated theguardian.com email" in {
      val req = FakeRequest().withCookies(guardianEmployeeCookieTheguardian)
      val defaultAttribsWithDigipackOverride =
        Attributes(guardianEmployeeUserTheguardian.id)
          .copy(DigitalSubscriptionExpiryDate = digipackAllowEmployeeAccessDateHack)
      contentAsJson(controller.attributes(req)) shouldEqual Json.toJson(defaultAttribsWithDigipackOverride)
    }

    "allow DigiPack access via hack to guardian employees with affecting other products" in {
      val req = FakeRequest().withCookies(validEmployeeUserCookie)
      contentAsJson(controller.attributes(req)) shouldEqual
        Json.toJson(testAttributes.copy(DigitalSubscriptionExpiryDate = digipackAllowEmployeeAccessDateHack))
    }

  }

  override def afterAll() = as.terminate()
}
