package services

import java.util.{Date, GregorianCalendar}

import scalaz.\/

import scala.concurrent.Future
import services.ContributionsStoreDatabaseService.DatabaseGetResult
import models.{ContributionData, RecurringReminderStatus, SupportReminders}


case class FakePostgresService(validId: String) extends ContributionsStoreDatabaseService {
    val testContributionData = ContributionData(
        created = new GregorianCalendar(2021, 10, 28).getTime,
        currency = "GBP",
        amount =  11.0,
        status =  "statusValue"
    )
    def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]] =
        if (identityId == validId)
            Future.successful(\/.right(List(testContributionData)))
        else
            Future.successful(\/.right(Nil))

    def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]] =
        Future.successful(\/.right(None))

    def getSupportReminders(identityId: String): DatabaseGetResult[SupportReminders] =
        Future.successful(\/.right(SupportReminders(RecurringReminderStatus.NotSet, None)))
}
