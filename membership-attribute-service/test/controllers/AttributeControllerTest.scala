package controllers

import actions.{AuthAndBackendRequest, AuthenticatedUserAndBackendRequest, CommonActions, HowToHandleRecencyOfSignedIn}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.gu.identity.model.{StatusFields, User}
import com.gu.identity.{RedirectAdviceResponse, SignedInRecently}
import components.{TouchpointBackends, TouchpointComponents}
import configuration.Config
import models.{Attributes, MobileSubscriptionStatus}
import org.joda.time.LocalDate
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import services.{AuthenticationService, FakePostgresService, MobileSubscriptionService}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AttributeControllerTest extends Specification with AfterAll with Mockito {

  implicit val as: ActorSystem = ActorSystem("test")

  private val validUserId = "123"
  private val userWithoutAttributesUserId = "456"
  private val unvalidatedEmailUserId = "789"

  private val testAttributes = Attributes(
    UserId = validUserId,
    Tier = Some("patron"),
    MembershipJoinDate = Some(new LocalDate(2017, 5, 13)),
    RecurringContributionPaymentPlan = Some("Monthly Contribution"),
    DigitalSubscriptionExpiryDate = Some(new LocalDate(2100, 1, 1)),
    PaperSubscriptionExpiryDate = Some(new LocalDate(2099, 1, 1)),
    GuardianWeeklySubscriptionExpiryDate = Some(new LocalDate(2099, 1, 1)),
  )

  private val validUserCookie = Cookie("validUser", "true")
  private val validUnvalidatedEmailCookie = Cookie("unvalidatedEmailUser", "true")
  private val userWithoutAttributesCookie = Cookie("invalidUser", "true")
  private val validUser = User(
    primaryEmailAddress = "test@gu.com",
    id = validUserId,
    statusFields = StatusFields(userEmailValidated = Some(true)),
  )
  private val unvalidatedEmailUser = User(
    primaryEmailAddress = "unvalidatedEmail@gu.com",
    id = unvalidatedEmailUserId,
    statusFields = StatusFields(userEmailValidated = Some(false)),
  )
  private val userWithoutAttributes = User(
    primaryEmailAddress = "notcached@gu.com",
    id = userWithoutAttributesUserId,
  )

  private val guardianEmployeeUser = User(
    primaryEmailAddress = "foo@guardian.co.uk",
    id = "1234321",
    statusFields = StatusFields(userEmailValidated = Some(true)),
  )
  private val guardianEmployeeCookie = Cookie("employeeDigiPackHack", "true")

  private val guardianEmployeeUserTheguardian = User(
    primaryEmailAddress = "foo@theguardian.com",
    id = "123theguardiancom",
    statusFields = StatusFields(userEmailValidated = Some(true)),
  )
  private val guardianEmployeeCookieTheguardian = Cookie("employeeDigiPackHackTheguardian", "true")

  private val validEmployeeUser = User(
    primaryEmailAddress = "bar@theguardian.com",
    id = "userWithRealProducts",
    statusFields = StatusFields(userEmailValidated = Some(true)),
  )
  private val validEmployeeUserCookie = Cookie("userWithRealProducts", "true")

  private val fakeAuthService = new AuthenticationService {
    override def user(implicit request: RequestHeader) =
      request.cookies.headOption match {
        case Some(c) if c == validUserCookie => Future.successful(Some(validUser))
        case Some(c) if c == validUnvalidatedEmailCookie => Future.successful(Some(unvalidatedEmailUser))
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

      val redirectAdviceResponse = RedirectAdviceResponse(SignedInRecently, None, None, None, None)

      Future(Right(new AuthAndBackendRequest[A](redirectAdviceResponse, components, request)))
    }
  }

  private val actorSystem = ActorSystem()
  private val touchpointBackends = new TouchpointBackends(actorSystem)
  private val stubParser = Helpers.stubBodyParser(AnyContent("test"))
  private val ex = scala.concurrent.ExecutionContext.global
  private val commonActions = new CommonActions(touchpointBackends, stubParser)(scala.concurrent.ExecutionContext.global, ActorMaterializer()) {
    override val AuthAndBackendViaAuthLibAction = NoCacheAction andThen FakeAuthAndBackendViaAuthLibAction
    override def AuthAndBackendViaIdapiAction(howToHandleRecencyOfSignedIn: HowToHandleRecencyOfSignedIn) =
      NoCacheAction andThen FakeAuthAndBackendViaIdapiAction
  }

  object FakeMobileSubscriptionService extends MobileSubscriptionService {
    override def getSubscriptionStatusForUser(identityId: String): Future[Either[String, Option[MobileSubscriptionStatus]]] =
      Future.successful(Right(None))
  }

  private val controller =
    new AttributeController(commonActions, Helpers.stubControllerComponents(), FakePostgresService(validUserId), FakeMobileSubscriptionService) {
      override val executionContext = scala.concurrent.ExecutionContext.global
      override def getSupporterProductDataAttributes(
          identityId: String,
      )(implicit request: AuthenticatedUserAndBackendRequest[AnyContent]): Future[(String, Option[Attributes])] = Future {
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

  private def verifyIdentityHeadersSet(result: Future[Result], expectedUserId: String, expectedTestUser: Boolean = false) = {
    val resultHeaders = headers(result)
    resultHeaders.get("X-Gu-Identity-Id") should beSome(expectedUserId)
    resultHeaders.get("X-Gu-Membership-Test-User") should beSome(expectedTestUser.toString)

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
                   |     "guardianWeeklySubscriber": true,
                   |     "guardianPatron": false
                   |   }
                   | }
                 """.stripMargin)
  }

  private def verifySuccessfullOneOfContributionsResult(result: Future[Result]) = {
    status(result) shouldEqual OK
    val jsonBody = contentAsJson(result)
    jsonBody shouldEqual
      Json.parse("""[
                   | {
                   |   "created":1638057600000,
                   |   "currency":"GBP",
                   |   "amount":11,
                   |   "status":"statusValue"
                   | }
                   | ]
                 """.stripMargin)
  }
  "getMyMembershipAttributesFeatures" should {
    "return unauthorised when cookies not provided" in {
      val req = FakeRequest()
      val result1 = controller.membership(req)
      val result2 = controller.attributes(req)
      val result3 = controller.features(req)
      val result4 = controller.oneOffContributions(req)

      status(result1) shouldEqual UNAUTHORIZED
      status(result2) shouldEqual UNAUTHORIZED
      status(result3) shouldEqual UNAUTHORIZED
      status(result4) shouldEqual UNAUTHORIZED
    }

    "return not found and set identity headers for unknown users in membership" in {
      val req = FakeRequest().withCookies(userWithoutAttributesCookie)
      val result = controller.membership(req)

      status(result) shouldEqual NOT_FOUND
      verifyIdentityHeadersSet(result, userWithoutAttributesUserId)
    }

    "return all false attributes and set identity headers for unknown users" in {
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
                     |    "guardianWeeklySubscriber": false,
                     |    "guardianPatron": false
                     |  }
                     |}""".stripMargin)
      verifyIdentityHeadersSet(result, userWithoutAttributesUserId)

    }

    "retrieve default features and set identity headers for unknown users" in {
      val req = FakeRequest().withCookies(userWithoutAttributesCookie)

      val result = controller.features(req)
      verifyDefaultFeaturesResult(result)
      verifyIdentityHeadersSet(result, userWithoutAttributesUserId)

    }

    "retrieve features and set identity headers for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.features(req)

      verifySuccessfulFeaturesResult(result)
      verifyIdentityHeadersSet(result, validUser.id)

    }

    "retrieve membership attributes and set identity headers for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.membership(req)

      verifySuccessfulMembershipResult(result)
      verifyIdentityHeadersSet(result, validUser.id)

    }

    "retrieve all the attributes and set identity headers for user in cookie" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.attributes(req)

      verifySuccessfullAttributesResult(result)
      verifyIdentityHeadersSet(result, validUser.id)

    }

    "return unauthorised and set identity headers when attempting to retrieve one off contributions for user with a non validated email" in {
      val req = FakeRequest().withCookies(validUnvalidatedEmailCookie)
      val result: Future[Result] = controller.oneOffContributions(req)
      status(result) shouldEqual 401
      verifyIdentityHeadersSet(result, unvalidatedEmailUser.id)
    }

    "return one off contributions and set identity headers for user with a validated email" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.oneOffContributions(req)

      verifySuccessfullOneOfContributionsResult(result)
      verifyIdentityHeadersSet(result, validUser.id)
    }

    "return unauthorised and set identity headers for user with a validated email but not contributions" in {
      val req = FakeRequest().withCookies(validUserCookie)
      val result: Future[Result] = controller.oneOffContributions(req)

      verifySuccessfullOneOfContributionsResult(result)
      verifyIdentityHeadersSet(result, validUser.id)
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

  override def afterAll(): Unit = as.terminate()
}
