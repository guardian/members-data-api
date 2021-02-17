package models

import java.util.Date

import anorm.{RowParser, Macro, Row, Success, ~}
import play.api.libs.json.{Json, Writes}
import org.scalactic.Bool

sealed trait RecurringReminderStatus 


object RecurringReminderStatus {
  case object NotSet extends RecurringReminderStatus
  case object Active extends RecurringReminderStatus
  case object Cancelled extends RecurringReminderStatus

  implicit val writes = new Writes[RecurringReminderStatus] {
    def writes(status: RecurringReminderStatus) = status match {
      case NotSet => Json.toJson("NotSet")
      case Active => Json.toJson("Active")
      case Cancelled => Json.toJson("Cancelled")
    }
  }
}

case class SupportReminderDb (
  isCancelled: Boolean,
)

object SupportReminderDb {
  val supportReminderDbRowParser: RowParser[SupportReminderDb] = Macro.indexedParser[SupportReminderDb]
}

case class SupportReminders (
  recurringStatus: RecurringReminderStatus,
)

object SupportReminders {
  implicit val jsWrite = Json.writes[SupportReminders]
}
