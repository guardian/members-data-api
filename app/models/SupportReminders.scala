package models

import anorm.{Macro, RowParser}
import play.api.libs.json.{Json, Writes}

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

case class SupportReminderDb(
    is_cancelled: Boolean,
    reminder_code: java.util.UUID,
)

object SupportReminderDb {
  val supportReminderDbRowParser: RowParser[SupportReminderDb] = Macro.indexedParser[SupportReminderDb]
}

case class SupportReminders(
    recurringStatus: RecurringReminderStatus,
    recurringReminderCode: Option[String],
)

object SupportReminders {
  implicit val jsWrite = Json.writes[SupportReminders]
}
