package services

import anorm._
import com.typesafe.scalalogging.StrictLogging
import models.ContributionData
import play.api.db.Database
import services.OneOffContributionDatabaseService.DatabaseGetResult

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scalaz.\/


trait OneOffContributionDatabaseService {
  def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]]

  def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]]
}

object OneOffContributionDatabaseService {
  type DatabaseGetResult[R] = Future[\/[String, R]]
}

class PostgresDatabaseService private (database: Database)(implicit ec: ExecutionContext)
  extends OneOffContributionDatabaseService with StrictLogging {

  private def executeQuery[R](statement: SimpleSql[Row], parser: ResultSetParser[R]): DatabaseGetResult[R] =
    Future(database.withConnection { implicit conn =>
      statement.as(parser)
    })
      .map(\/.right)
      .recover { case NonFatal(err) =>
        \/.left(s"Error querying contributions store. Error: $err")
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
}

object PostgresDatabaseService {
  def fromDatabase(database: Database)(implicit ec: ExecutionContext): PostgresDatabaseService =
    new PostgresDatabaseService(database)
}

