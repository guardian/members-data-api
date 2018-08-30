package services

import anorm._
import cats.data.EitherT
import cats.syntax.applicativeError._
import cats.instances.future._
import com.typesafe.scalalogging.StrictLogging
import models.ContributionData
import play.api.db.Database

import scala.concurrent.{ExecutionContext, Future}


trait DatabaseService {

  // If an insert is unsuccessful then an error should be logged, however,
  // the return type is not modelled as an EitherT,
  // since the result of the insert has no dependencies.
  // See e.g. backend.StripeBackend for more context.
  def getContributionsData(identityId: String): EitherT[Future, DatabaseService.Error, List[ContributionData]]
}

object DatabaseService {
  case class Error(message: String, err: Option[Throwable]) extends Exception {
    override def getMessage: String = err.fold(message)(error => s"$message - ${error.getMessage}")
  }
}

class PostgresDatabaseService private (database: Database)(implicit ec: ExecutionContext)
  extends DatabaseService with StrictLogging {


  private def executeQuery(statement: SimpleSql[Row]): EitherT[Future, DatabaseService.Error, List[ContributionData]] =
    Future(database.withConnection { implicit conn =>
      val allRowsParser: ResultSetParser[List[ContributionData]] = ContributionData.contributionRowParser.*
      statement.as(allRowsParser)
    })
      .attemptT
      .leftMap(
        err => {
          val msg = s"Error querying contributions store"
          logger.error(s"$msg. Error: $err")
          DatabaseService.Error(msg, Some(err))
        }
      )



  override def getContributionsData(identityId: String): EitherT[Future, DatabaseService.Error, List[ContributionData]] = {

    val statement = SQL"""
      SELECT received_timestamp, currency, amount, status
      FROM contributions
      WHERE identity_id = $identityId
    """
    executeQuery(statement)

  }
}

object PostgresDatabaseService {
  def fromDatabase(database: Database)(implicit ec: ExecutionContext): PostgresDatabaseService =
    new PostgresDatabaseService(database)
}

