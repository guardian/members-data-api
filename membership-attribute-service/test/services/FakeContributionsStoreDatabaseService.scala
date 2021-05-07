package services

import scalaz.\/
import scala.concurrent.Future
import services.ContributionsStoreDatabaseService.DatabaseGetResult
import models.{ContributionData, SupportReminders, RecurringReminderStatus}


object FakePostgresService extends ContributionsStoreDatabaseService {
    def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]] =
    Future.successful(\/.right(Nil))

    def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]] =
        Future.successful(\/.right(None))

    def getSupportReminders(identityId: String): DatabaseGetResult[SupportReminders] =
        Future.successful(\/.right(SupportReminders(RecurringReminderStatus.NotSet, None)))
}
