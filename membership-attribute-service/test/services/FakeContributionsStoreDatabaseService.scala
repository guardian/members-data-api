package services

import com.gu.i18n.Currency

import java.util.GregorianCalendar
import scala.concurrent.Future
import services.ContributionsStoreDatabaseService.DatabaseGetResult
import models.{ContributionData, RecurringReminderStatus, SupportReminders}

case class FakePostgresService(validId: String) extends ContributionsStoreDatabaseService {
  val testContributionData = ContributionData(
    created = new GregorianCalendar(2021, 10, 28).getTime,
    currency = Currency.GBP.iso,
    amount = 11.0,
    status = "statusValue",
    payment_provider = "Stripe",
    refunded = None,
    payment_id = "ch_123456789abc",
  )
  def getAllContributions(identityId: String): DatabaseGetResult[List[ContributionData]] =
    if (identityId == validId)
      Future.successful(Right(List(testContributionData)))
    else
      Future.successful(Right(Nil))

  def getLatestContribution(identityId: String): DatabaseGetResult[Option[ContributionData]] =
    Future.successful(Right(None))

  def getSupportReminders(identityId: String): DatabaseGetResult[SupportReminders] =
    Future.successful(Right(SupportReminders(RecurringReminderStatus.NotSet, None)))
}
