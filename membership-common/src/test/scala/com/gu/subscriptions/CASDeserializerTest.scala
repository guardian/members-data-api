package com.gu.subscriptions

import org.specs2.mutable.Specification
import utils.Resource

import CAS._
import CAS.Deserializer._

class CASDeserializerTest extends Specification {
  "CASDeserializer" should {
    "deserialize a success" in {
      val successOpt = Resource.getJson("cas/valid-subscription.json").asOpt[CASSuccess]
      successOpt must_=== Some(CASSuccess("sub", Some("provider"), "2030-02-24", Some("XXX"), "CONTENT"))
    }

    "deserialize a success with no optional field" in {
      val successOpt = Resource.getJson("cas/valid-subscription-optional-fields.json").asOpt[CASSuccess]
      successOpt must_=== Some(CASSuccess("sub", None, "2030-02-24", None, "CONTENT"))
    }

    "deserialize an error" in {
      val errorOpt = Resource.getJson("cas/error.json").asOpt[CASError]
      errorOpt must_=== Some(CASError("Unknown subscriber", Some(-90)))
    }
  }
}
