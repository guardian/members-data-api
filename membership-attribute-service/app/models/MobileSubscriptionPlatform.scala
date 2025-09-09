package models

import play.api.libs.json._

sealed trait MobileSubscriptionPlatform {
  def value: String
}

object MobileSubscriptionPlatform {
  case object Ios extends MobileSubscriptionPlatform {
    val value = "ios"
  }
  
  case object Android extends MobileSubscriptionPlatform {
    val value = "android"
  }
  
  case object DailyEdition extends MobileSubscriptionPlatform {
    val value = "newsstand"
  }
  
  case object IosEdition extends MobileSubscriptionPlatform {
    val value = "ios-edition"
  }
  
  case object AndroidEdition extends MobileSubscriptionPlatform {
    val value = "android-edition"
  }
  
  case object IosPuzzles extends MobileSubscriptionPlatform {
    val value = "ios-puzzles"
  }
  
  case object AndroidPuzzles extends MobileSubscriptionPlatform {
    val value = "android-puzzles"
  }
  
  case object IosFeast extends MobileSubscriptionPlatform {
    val value = "ios-feast"
  }
  
  case object AndroidFeast extends MobileSubscriptionPlatform {
    val value = "android-feast"
  }

  val all: Seq[MobileSubscriptionPlatform] = Seq(
    Ios, Android, DailyEdition, IosEdition, AndroidEdition,
    IosPuzzles, AndroidPuzzles, IosFeast, AndroidFeast
  )

  def fromString(value: String): Option[MobileSubscriptionPlatform] = {
    all.find(_.value == value)
  }

  // JSON serialization/deserialization
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
