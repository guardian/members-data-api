package services

import java.time.LocalDateTime

import akka.http.scaladsl.model.HttpCharsetRange.*
import anorm._
import cats.data.EitherT
import cats.syntax.applicativeError._
import cats.instances.future._
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import models.{ContributionData, Currency}
import play.api.db.Database

import scala.concurrent.{ExecutionContext, Future}


trait DatabaseService {

  // If an insert is unsuccessful then an error should be logged, however,
  // the return type is not modelled as an EitherT,
  // since the result of the insert has no dependencies.
  // See e.g. backend.StripeBackend for more context.
  def getContributionsData(identityId: String): EitherT[Future, DatabaseService.Error, ContributionData]
}

object DatabaseService {
  case class Error(message: String, err: Option[Throwable]) extends Exception {
    override def getMessage: String = err.fold(message)(error => s"$message - ${error.getMessage}")
  }
}

class PostgresDatabaseService private (database: Database)(implicit ec: ExecutionContext)
  extends DatabaseService with StrictLogging {

  def timeFromString(time: String): LocalDateTime = {
    import java.time.format.DateTimeFormatter
    val formatter = DateTimeFormatter.ofPattern("yyyy-MMM-dd hh:mm:ss")
    LocalDateTime.parse(time, formatter)
  }


  private def executeQuery(statement: SimpleSql[Row]): EitherT[Future, DatabaseService.Error, ContributionData] =
    Future(database.withConnection { implicit conn =>
      val contributionRowParser: RowParser[ContributionData] = (
        SqlParser.str("created") ~
          SqlParser.str("currency") ~
          SqlParser.double("amount")
        ).map {
        case created ~ currency ~ amount => ContributionData(created, currency, amount)
      }

      val allRowsParser: ResultSetParser[ContributionData] = contributionRowParser.single
       statement.as(allRowsParser)
    })
      .attemptT
      .leftMap(
        err => {
          val msg = "unable to insert contribution into database"
          logger.error(s"$msg. Error: $err")
          DatabaseService.Error(msg, Some(err))
        }
      )



  override def getContributionsData(identityId: String): EitherT[Future, DatabaseService.Error, ContributionData] = {

    val statement = SQL"""
      SELECT created, currency, amount
      FROM contributions
      WHERE identity_id = '$identityId'
    """
    executeQuery(statement)

  }
}

object PostgresDatabaseService {
  def fromDatabase(database: Database)(implicit ec: ExecutionContext): PostgresDatabaseService =
    new PostgresDatabaseService(database)
}

