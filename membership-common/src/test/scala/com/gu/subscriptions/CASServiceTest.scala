package com.gu.subscriptions

import com.gu.memsub.Subscription.Name
import com.gu.subscriptions.CAS.Deserializer._
import com.gu.subscriptions.CAS.{CASError, CASSuccess}
import com.gu.subscriptions.Quadrant.looksLikeAQuadrantSubscriber
import org.scalatest.concurrent.ScalaFutures
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import utils.Resource

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CASServiceTest extends Specification with Mockito with ScalaFutures {

  "CASService" should {

    val subscriber = Resource.getJson("cas/valid-subscription.json").as[CASSuccess]
    val subscriberError = Resource.getJson("cas/error.json").as[CASError]

    "identify Quadrant-like subscriber ids" in {
      looksLikeAQuadrantSubscriber(Name("00123456")) mustEqual true

      looksLikeAQuadrantSubscriber(Name("000123456")) mustEqual false

      looksLikeAQuadrantSubscriber(Name("00xxxxxx")) mustEqual false
    }

    "return a success for subscription with valid password" in {
      val CASApi = mock[CASApi]

      CASApi.check(Name("some-subscriber-id"), "password", triggersActivation = true) returns Future.successful(subscriber)

      val CASService = new CASService(CASApi)

      whenReady(CASService.check(Name("some-subscriber-id"), "password")) { result =>
        val CASSuccess = result.asInstanceOf[CASSuccess]
        CASSuccess.subscriptionCode.must_===(Some("XXX"))
      }
    }

    "return a success for any Quadrant id, because their API does not include Voucher subscribers" in {
      val CASApi = mock[CASApi]

      CASApi.check(Name("00123456"), "password", triggersActivation = true) returns Future.successful(subscriberError)

      val CASService = new CASService(CASApi)

      whenReady(CASService.check(Name("00123456"), "password")) { result =>
        val CASSuccess = result.asInstanceOf[CASSuccess]
        CASSuccess.subscriptionCode.must_===(Some("Unknown"))
      }
    }

    "return a error for subscription with incorrect password" in {
      val CASApi = mock[CASApi]
      CASApi.check(Name("some-subscriber-id"), "wrong-password", triggersActivation = true) returns Future.successful(subscriberError)

      val CASService = new CASService(CASApi)

      whenReady(CASService.check(Name("some-subscriber-id"), "wrong-password")) { result =>
        val r = result.asInstanceOf[CASError]
        r.message.mustEqual("Unknown subscriber")
      }
    }
  }
}