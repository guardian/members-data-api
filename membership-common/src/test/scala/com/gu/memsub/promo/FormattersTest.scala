package com.gu.memsub.promo

import com.amazonaws.services.dynamodbv2.document.Item
import com.github.nscala_time.time.Imports._
import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub.images.{ResponsiveImage, ResponsiveImageGroup}
import com.gu.memsub.promo.CampaignGroup.{DigitalPack, GuardianWeekly, Membership, Newspaper}
import com.gu.memsub.promo.Formatters.CampaignFormatters._
import com.gu.memsub.promo.Formatters.PromotionFormatters._
import com.gu.memsub.promo.Formatters._
import com.gu.memsub.promo.Promotion.AnyPromotion
import com.gu.memsub.promo.PromotionStub._
import com.gu.memsub.services.JsonDynamoService
import io.lemonlabs.uri.dsl._
import org.joda.time.{DateTimeZone, Days}
import org.joda.time.format.ISODateTimeFormat
import org.specs2.mutable.Specification
import play.api.libs.json._
import utils.Resource

class FormattersTest extends Specification {

  implicit val itemFormatter = JsonDynamoService.itemFormat

  val errors: Seq[PromoError] = Seq(InvalidCountry, InvalidProductRatePlan, NoSuchCode, ExpiredPromotion)
  val prpId = ProductRatePlanId("test")

  for (error: PromoError <- errors) {
    "Each PromoError" should {

      val json = Json.toJson(error)
      "return the correct error string" in {
        (json \ "errorMessage").get.as[String] must_== error.msg
      }
    }
  }

  "Promotion formatters" should {

    "Write promo channels as a JSON map" in {
      val promo = promoFor("PARTNER99", prpId)
      (Json.toJson(promo) \ "codes").get mustEqual Json.obj(
        "testChannel" -> JsArray(Seq(JsString("PARTNER99"))),
      )
    }

    "Write datetimes as ISO 8601" in {
      val promo = promoFor("PARTNER99", prpId)
      // .toString(ISODateTimeFormat.dateTime()) does not work when in GMT ->
      // http://stackoverflow.com/questions/27920239/jodatime-datetime-with-0000-instead-z
      (Json.toJson(promo) \ "starts").get.as[String] mustEqual promo.starts.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    }

    "Read DoubleType promotion" in {
      val doubleTypePromo = DoubleType[NewUsers](Tracking, PercentDiscount(Some(3), 20d))
      Resource.getJson("promo/subscriptions/double-type.json").as[AnyPromotion].promotionType mustEqual doubleTypePromo
    }

    "Write DoubleType promotion" in {
      val doubleTypePromo = DoubleType[NewUsers](Tracking, PercentDiscount(Some(3), 20d))
      val promo: AnyPromotion = promoFor("PARTNER99", prpId).ofType[DoubleType[NewUsers]](doubleTypePromo)
      val jsonOutput = Json.toJson(promo)
      jsonOutput.as[AnyPromotion].promotionType mustEqual doubleTypePromo
    }

    "Read an incentive promotion correctly" in {
      val incentive = Incentive("this", Some("thing"), Some("legalese"))
      Resource.getJson("promo/subscriptions/incentive.json").as[AnyPromotion].promotionType mustEqual incentive
      Resource.getJson("promo/membership/incentive.json").as[AnyPromotion].promotionType mustEqual incentive
    }

    "Read a PercentDiscount promotion correctly" in {
      val discount = PercentDiscount(None, 50L)
      Resource.getJson("promo/subscriptions/discount.json").as[AnyPromotion].promotionType mustEqual discount
      Resource.getJson("promo/membership/discount.json").as[AnyPromotion].promotionType mustEqual discount
    }

    "Read a FreeTrial promotion correctly" in {
      val trial = FreeTrial(Days.days(30))
      Resource.getJson("promo/subscriptions/freetrial.json").as[AnyPromotion].promotionType mustEqual trial
    }

    "Read a Tracking promotion correctly" in {
      Resource.getJson("promo/subscriptions/tracking.json").as[AnyPromotion].promotionType mustEqual Tracking
      Resource.getJson("promo/membership/tracking.json").as[AnyPromotion].promotionType mustEqual Tracking
    }

    "Read a Retention promotion correctly" in {
      Resource.getJson("promo/subscriptions/retention.json").as[AnyPromotion].promotionType mustEqual Retention
    }

    "Fail to read a promotion with an invalid type" in {
      Resource.getJson("promo/subscriptions/invalid-type.json").validate[AnyPromotion].isError
      Resource.getJson("promo/membership/invalid-type.json").validate[AnyPromotion].isError
    }

    "Fail to read a promotion with an invalid landing page colour" in {
      Resource.getJson("promo/subscriptions/invalid-landingPage-sectionColour.json").validate[AnyPromotion].isError
    }
  }

  "LandingPage formatters" should {

    "Read a Membership LandingPage for an incentive correctly" in {
      val promotion = Resource.getJson("promo/membership/incentive.json").as[AnyPromotion].asMembership.get
      promotion.landingPage.subtitle.get mustEqual "Subtitle"
    }

    "Read a Membership LandingPage with a hero / non hero image correctly" in {
      val promotion = Resource.getJson("promo/membership/images.json").as[AnyPromotion].asMembership.get
      promotion.landingPage.heroImage.get mustEqual HeroImage(
        alignment = Bottom,
        image = ResponsiveImageGroup(availableImages =
          Seq(
            ResponsiveImage("http://example.com", 50),
            ResponsiveImage("http://example.com", 20),
            ResponsiveImage("http://example.com", 30),
          ),
        ),
      )
      promotion.landingPage.image.get mustEqual
        ResponsiveImageGroup(availableImages =
          Seq(
            ResponsiveImage("http://example.com", 5),
            ResponsiveImage("http://example.com", 2),
            ResponsiveImage("http://example.com", 3),
          ),
        )
    }

    "Read a Membership LandingPage for a discount correctly" in {
      val promotion = Resource.getJson("promo/membership/discount.json").as[AnyPromotion].asMembership.get
      promotion.landingPage.subtitle.get mustEqual "Subtitle"
    }

    "Read a Membership LandingPage for tracking correctly" in {
      val promotion = Resource.getJson("promo/membership/tracking.json").as[AnyPromotion].asMembership.get
      promotion.landingPage.subtitle.get mustEqual "Subtitle"
    }

  }

  "Campaign formatters" should {
    "Write campaigns successfully with sortDate" in {
      val json = Json.toJson[Campaign](Campaign(CampaignCode("Code"), Membership, "Name", Some(new DateTime(0, DateTimeZone.UTC))))
      (json \ "code").as[String] mustEqual "Code"
      (json \ "name").as[String] mustEqual "Name"
      (json \ "group").as[String] mustEqual Membership.id
      (json \ "sortDate").as[String] mustEqual "1970-01-01T00:00:00.000+00:00"
    }

    "Write campaigns successfully with no sortDate" in {
      val json = Json.toJson[Campaign](Campaign(CampaignCode("Code"), Membership, "Name", None))
      (json \ "code").as[String] mustEqual "Code"
      (json \ "name").as[String] mustEqual "Name"
      (json \ "group").as[String] mustEqual Membership.id
    }

    "Read a valid membership campaign - productFamily version" in {
      Resource.getJson("promo/campaign/membership.json").as[Campaign].group mustEqual Membership
    }

    "Read a valid digitalpack campaign - productFamily version" in {
      Resource.getJson("promo/campaign/digitalpack.json").as[Campaign].group mustEqual DigitalPack
    }

    "Read a valid newspaper campaign - product version" in {
      Resource.getJson("promo/campaign/newspaper.json").as[Campaign].group mustEqual Newspaper
    }

    "Read a valid guardian weekly campaign - product version" in {
      Resource.getJson("promo/campaign/weekly.json").as[Campaign].group mustEqual GuardianWeekly
    }

    "Fail to read an invalid campaign" in {
      Resource.getJson("promo/campaign/invalid.json").validate[Campaign].isError mustEqual true
    }
  }

  "HeroImageAlignment formatters" should {
    "Serialise and deserialise each type of alignment" in {
      Json.fromJson[HeroImageAlignment](Json.toJson(Top: HeroImageAlignment)) mustEqual JsSuccess(Top)
      Json.fromJson[HeroImageAlignment](Json.toJson(Centre: HeroImageAlignment)) mustEqual JsSuccess(Centre)
      Json.fromJson[HeroImageAlignment](Json.toJson(Bottom: HeroImageAlignment)) mustEqual JsSuccess(Bottom)
    }
  }

  "Item formatters" should {

    "Read scalar values into Items" in {
      Json.obj("test" -> "value").as[Item].getString("test") mustEqual "value"
      Json.obj("test" -> true).as[Item].getBoolean("test") mustEqual true
      Json.obj("test" -> JsNull).as[Item].get("test") mustEqual null
      Json.obj("test" -> 1).as[Item].getInt("test") mustEqual 1
    }

    "Write items into JSON" in {
      Json.toJson(new Item().withInt("test", 1234)) mustEqual Json.obj("test" -> JsNumber(1234))
      Json.toJson(new Item().withBoolean("test", true)) mustEqual Json.obj("test" -> JsBoolean(true))
      Json.toJson(new Item().withString("test", "test")) mustEqual Json.obj("test" -> JsString("test"))
      Json.toJson(new Item().withNull("test")) mustEqual Json.obj("test" -> JsNull)
    }

    "Read and write JSON arrays into Lists nested within an item" in {
      val array = JsArray(Seq(JsString("item1"), JsString("item2")))
      val testObject = Json.obj("test" -> array)

      val item = testObject.as[Item]
      item.getJSON("test") mustEqual array.toString()
      Json.toJson[Item](item) mustEqual testObject
    }

    "Read and write JSON objects into Maps nested within an item" in {
      val obj = Json.obj("a" -> "item1", "b" -> "item2")
      val testObject = Json.obj("test" -> obj)

      val item = testObject.as[Item]
      item.getJSON("test") mustEqual obj.toString()
      Json.toJson[Item](item) mustEqual testObject
    }

    "Serialise and deserialise a promotion losslessly" in {
      val promo: AnyPromotion = promoFor("PARTNER123", prpId).ofType(FreeTrial(Days.days(5)))
      Json.fromJson[AnyPromotion](Json.toJson(Json.fromJson[Item](Json.toJson(promo)).get)).get mustEqual promo
    }

    "Return JsError when trying to write a non JsObject" in {
      val jsValue = JsArray(Seq(JsString("foo"), JsString("bar"), JsString("boo"), JsString("far")))
      Json.fromJson[Item](jsValue).isError mustEqual true
    }
  }
}
