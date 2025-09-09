package models

import org.specs2.mutable.Specification
import play.api.libs.json.{JsString, JsSuccess, Json}

// Import Platform serializers
import Platform._

class PlatformTest extends Specification {

  "Platform enum" should {
    "serialize to correct string values" in {
      Json.toJson(Platform.Ios) mustEqual JsString("ios")
      Json.toJson(Platform.Android) mustEqual JsString("android")
      Json.toJson(Platform.DailyEdition) mustEqual JsString("newsstand")
      Json.toJson(Platform.IosEdition) mustEqual JsString("ios-edition")
      Json.toJson(Platform.AndroidEdition) mustEqual JsString("android-edition")
      Json.toJson(Platform.IosPuzzles) mustEqual JsString("ios-puzzles")
      Json.toJson(Platform.AndroidPuzzles) mustEqual JsString("android-puzzles")
      Json.toJson(Platform.IosFeast) mustEqual JsString("ios-feast")
      Json.toJson(Platform.AndroidFeast) mustEqual JsString("android-feast")
    }

    "deserialize from correct string values" in {
      Json.fromJson[Platform](JsString("ios")) mustEqual JsSuccess(Platform.Ios)
      Json.fromJson[Platform](JsString("android")) mustEqual JsSuccess(Platform.Android)
      Json.fromJson[Platform](JsString("newsstand")) mustEqual JsSuccess(Platform.DailyEdition)
      Json.fromJson[Platform](JsString("ios-edition")) mustEqual JsSuccess(Platform.IosEdition)
      Json.fromJson[Platform](JsString("android-edition")) mustEqual JsSuccess(Platform.AndroidEdition)
      Json.fromJson[Platform](JsString("ios-puzzles")) mustEqual JsSuccess(Platform.IosPuzzles)
      Json.fromJson[Platform](JsString("android-puzzles")) mustEqual JsSuccess(Platform.AndroidPuzzles)
      Json.fromJson[Platform](JsString("ios-feast")) mustEqual JsSuccess(Platform.IosFeast)
      Json.fromJson[Platform](JsString("android-feast")) mustEqual JsSuccess(Platform.AndroidFeast)
    }

    "find platforms using fromString" in {
      Platform.fromString("ios") mustEqual Some(Platform.Ios)
      Platform.fromString("android") mustEqual Some(Platform.Android)
      Platform.fromString("newsstand") mustEqual Some(Platform.DailyEdition)
      Platform.fromString("invalid") mustEqual None
    }
  }

  "MobileSubscriptionStatus with Platform" should {
    "serialize and deserialize correctly" in {
      val mobileStatus = MobileSubscriptionStatus(
        valid = true,
        to = org.joda.time.DateTime.now(),
        platform = Some(Platform.Ios)
      )

      val json = Json.toJson(mobileStatus)
      val parsed = Json.fromJson[MobileSubscriptionStatus](json)
      
      parsed.isSuccess must beTrue
      parsed.get.platform mustEqual Some(Platform.Ios)
    }

    "handle None platform correctly" in {
      val mobileStatus = MobileSubscriptionStatus(
        valid = true,
        to = org.joda.time.DateTime.now(),
        platform = None
      )

      val json = Json.toJson(mobileStatus)
      val parsed = Json.fromJson[MobileSubscriptionStatus](json)
      
      parsed.isSuccess must beTrue
      parsed.get.platform mustEqual None
    }
  }
}
