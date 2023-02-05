package subscriptions

import models.subscription.Subscription.Name
import org.mockito.IdiomaticMockito
import org.specs2.mutable.Specification
import subscriptions.CAS.Deserializer._
import subscriptions.CAS.{CASError, CASSuccess}
import subscriptions.Quadrant.looksLikeAQuadrantSubscriber
import util.Await.waitFor
import util.Resource

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CASServiceTest extends Specification with IdiomaticMockito {

  val subscriberError = Resource.getJson("cas/error.json").as[CASError]

  "CASService" should {

    val subscriber = Resource.getJson("cas/valid-subscription.json").as[CASSuccess]

    "identify Quadrant-like subscriber ids" in {
      looksLikeAQuadrantSubscriber(Name("00123456")) mustEqual true

      looksLikeAQuadrantSubscriber(Name("000123456")) mustEqual false

      looksLikeAQuadrantSubscriber(Name("00xxxxxx")) mustEqual false
    }

    "return a success for subscription with valid password" in {
      val CASApi = mock[CASApi]

      CASApi.check(Name("some-subscriber-id"), "password", triggersActivation = true) returns Future.successful(subscriber)

      val CASService = new CASService(CASApi)

      val CASSuccess = waitFor(CASService.check(Name("some-subscriber-id"), "password")).asInstanceOf[CASSuccess]
      CASSuccess.subscriptionCode.must_===(Some("XXX"))
    }

    "return a success for any Quadrant id, because their API does not include Voucher subscribers" in {
      val CASApi = mock[CASApi]

      CASApi.check(Name("00123456"), "password", triggersActivation = true) returns Future.successful(subscriberError)

      val CASService = new CASService(CASApi)

      val CASSuccess = waitFor(CASService.check(Name("00123456"), "password")).asInstanceOf[CASSuccess]
      CASSuccess.subscriptionCode.must_===(Some("Unknown"))
    }
  }

  "return a error for subscription with incorrect password" in {
    val CASApi = mock[CASApi]
    CASApi.check(Name("some-subscriber-id"), "wrong-password", triggersActivation = true) returns Future.successful(subscriberError)

    val CASService = new CASService(CASApi)

    val r = waitFor(CASService.check(Name("some-subscriber-id"), "wrong-password")).asInstanceOf[CASError]
    r.message.mustEqual("Unknown subscriber")
  }
}
