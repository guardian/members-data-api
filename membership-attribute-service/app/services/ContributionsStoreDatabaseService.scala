package services

import anorm._
import com.typesafe.scalalogging.StrictLogging
import models.RecurringReminderStatus._
import models.{ContributionData, SupportReminderDb, SupportReminders}
import play.api.db.Database
import services.ContributionsStoreDatabaseService.DatabaseGetResult

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

trait ContributionsStoreDatabaseService {
  def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]]

  def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]]

  def getSupportReminders(identityId: String): DatabaseGetResult[SupportReminders]
}

object ContributionsStoreDatabaseService {
  type DatabaseGetResult[R] = Future[Either[String, R]]
}

class PostgresDatabaseService private (database: Database)(implicit ec: ExecutionContext)
  extends ContributionsStoreDatabaseService with StrictLogging {

  private def executeQuery[R](statement: SimpleSql[Row], parser: ResultSetParser[R]): DatabaseGetResult[R] =
    Future(database.withConnection { implicit conn =>
      statement.as(parser)
    })
      .map(Right(_))
      .recover { case NonFatal(err) =>
        Left(s"Error querying contributions store. Error: $err")
      }

  override def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]] = {
    val statement = SQL"""
      SELECT received_timestamp, currency, amount, status
      FROM contributions
      WHERE identity_id = $identityId
    """
    val allRowsParser: ResultSetParser[List[ContributionData]] = ContributionData.contributionRowParser.*

    executeQuery(statement, allRowsParser)
  }

  override def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]] = {
    val statement = SQL"""
      SELECT received_timestamp, currency, amount, status
      FROM contributions
      WHERE identity_id = $identityId
      AND status = 'Paid'
      ORDER BY received_timestamp desc
      LIMIT 1
    """
    val latestRowParser: ResultSetParser[Option[ContributionData]] = ContributionData.contributionRowParser.singleOpt

    executeQuery(statement, latestRowParser)
  }

  override def getSupportReminders(identityId: String): ContributionsStoreDatabaseService.DatabaseGetResult[SupportReminders] = {
    val statement = SQL"""
      SELECT
        reminder_cancelled_at IS NOT NULL as is_cancelled,
        reminder_code
      FROM recurring_reminder_signups
      WHERE identity_id = $identityId
    """
    val rowParser: ResultSetParser[Option[SupportReminderDb]] = SupportReminderDb.supportReminderDbRowParser.singleOpt

    executeQuery(statement, rowParser).map { result =>
      result.map {
        case Some(SupportReminderDb(true, reminderCode)) => SupportReminders(recurringStatus=Cancelled, recurringReminderCode=Some(reminderCode.toString()))
        case Some(SupportReminderDb(false, reminderCode)) => SupportReminders(recurringStatus=Active, recurringReminderCode=Some(reminderCode.toString()))
        case None => SupportReminders(recurringStatus=NotSet, recurringReminderCode=None)
      }
    }
  }
}

object PostgresDatabaseService {
  def fromDatabase(database: Database)(implicit ec: ExecutionContext): PostgresDatabaseService =
    new PostgresDatabaseService(database)
}

