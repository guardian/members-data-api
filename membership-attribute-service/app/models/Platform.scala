package models

import play.api.libs.json._

sealed trait Platform {
  def value: String
}

object Platform {
  case object Ios extends Platform {
    val value = "ios"
  }
  
  case object Android extends Platform {
    val value = "android"
  }
  
  case object DailyEdition extends Platform {
    val value = "newsstand"
  }
  
  case object IosEdition extends Platform {
    val value = "ios-edition"
  }
  
  case object AndroidEdition extends Platform {
    val value = "android-edition"
  }
  
  case object IosPuzzles extends Platform {
    val value = "ios-puzzles"
  }
  
  case object AndroidPuzzles extends Platform {
    val value = "android-puzzles"
  }
  
  case object IosFeast extends Platform {
    val value = "ios-feast"
  }
  
  case object AndroidFeast extends Platform {
    val value = "android-feast"
  }

  val all: Seq[Platform] = Seq(
    Ios, Android, DailyEdition, IosEdition, AndroidEdition,
    IosPuzzles, AndroidPuzzles, IosFeast, AndroidFeast
  )

  def fromString(value: String): Option[Platform] = {
    all.find(_.value == value)
  }

  // JSON serialization/deserialization
  implicit val platformWrites: Writes[Platform] = new Writes[Platform] {
    def writes(platform: Platform): JsValue = JsString(platform.value)
  }

  implicit val platformReads: Reads[Platform] = new Reads[Platform] {
    def reads(json: JsValue): JsResult[Platform] = json match {
      case JsString(value) => 
        fromString(value) match {
          case Some(platform) => JsSuccess(platform)
          case None => JsError(s"Invalid platform value: $value")
        }
      case _ => JsError("Platform must be a string")
    }
  }
}
