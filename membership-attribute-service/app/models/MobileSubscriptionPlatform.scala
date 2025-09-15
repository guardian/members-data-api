package models

import play.api.libs.json._

sealed abstract class MobileSubscriptionPlatform(val value: String)

object MobileSubscriptionPlatform {
  case object Ios extends MobileSubscriptionPlatform("ios")
  case object Android extends MobileSubscriptionPlatform("android")
  case object DailyEdition extends MobileSubscriptionPlatform("newsstand")
  case object IosEdition extends MobileSubscriptionPlatform("ios-edition")
  case object AndroidEdition extends MobileSubscriptionPlatform("android-edition")
  case object IosPuzzles extends MobileSubscriptionPlatform("ios-puzzles")
  case object AndroidPuzzles extends MobileSubscriptionPlatform("android-puzzles")
  case object IosFeast extends MobileSubscriptionPlatform("ios-feast")
  case object AndroidFeast extends MobileSubscriptionPlatform("android-feast")

  def fromString(value: String): Option[MobileSubscriptionPlatform] = {
    value match {
      case Ios.value => Some(Ios)
      case Android.value => Some(Android)
      case DailyEdition.value => Some(DailyEdition)
      case IosEdition.value => Some(IosEdition)
      case AndroidEdition.value => Some(AndroidEdition)
      case IosPuzzles.value => Some(IosPuzzles)
      case AndroidPuzzles.value => Some(AndroidPuzzles)
      case IosFeast.value => Some(IosFeast)
      case AndroidFeast.value => Some(AndroidFeast)
      case _ => None
    }
  }

  implicit val platformWrites: Writes[MobileSubscriptionPlatform] = new Writes[MobileSubscriptionPlatform] {
    def writes(platform: MobileSubscriptionPlatform): JsValue = JsString(platform.value)
  }

  implicit val platformReads: Reads[MobileSubscriptionPlatform] = new Reads[MobileSubscriptionPlatform] {
    def reads(json: JsValue): JsResult[MobileSubscriptionPlatform] = json match {
      case JsString(value) =>
        fromString(value) match {
          case Some(platform) => JsSuccess(platform)
          case None => JsError(s"Invalid platform value: $value")
        }
      case _ => JsError("Platform must be a string")
    }
  }
}
