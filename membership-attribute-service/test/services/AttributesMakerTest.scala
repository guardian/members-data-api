package services

import com.github.nscala_time.time.Implicits._
import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.rest.ZuoraRestService.{PaymentMethodId, PaymentMethodResponse}
import models.{Attributes, AccountWithSubscriptions, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import testdata.SubscriptionTestData
import testdata.AccountObjectTestData._

import scala.concurrent.Future
import scalaz.\/

class AttributesMakerTest(implicit ee: ExecutionEnv)  extends Specification with SubscriptionTestData {
  override def referenceDate = new LocalDate()

  val referenceDateAsDynamoTimestamp = referenceDate.toDateTimeAtStartOfDay.getMillis / 1000
  val identityId = "123"

  def paymentMethodResponseNoFailures(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(0, "CreditCardReferenceTransaction", referenceDate.toDateTimeAtCurrentTime)))
  def paymentMethodResponseRecentFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusDays(1))))
  def paymentMethodResponseStaleFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusMonths(2))))

  "zuoraAttributes" should {
    val anotherAccountSummary = accountObjectWithZeroBalance.copy(Id = AccountId("another accountId"))

    "return attributes when digipack sub" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(digipack))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return attributes when one of the subs has a digital benefit" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year),
        PaperSubscriptionExpiryDate = Some(referenceDate + 1.year),
        GuardianWeeklySubscriptionExpiryDate = None
      ))
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(sunday)), AccountWithSubscriptions(anotherAccountSummary, List(sundayPlus))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return none when only sub is expired" in {
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(expiredMembership))), paymentMethodResponseNoFailures, referenceDate)
      result must beNone.await
    }

    "return attributes when there is one membership sub" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(membership))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return attributes when one sub is expired and one is not" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None
      )
      )

      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(expiredMembership)), AccountWithSubscriptions(accountObjectWithZeroBalance, List(contributor))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await

    }

    "return attributes when one sub is a recurring contribution" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(contributor))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await

    }

    "return attributes relevant to both when one sub is a contribution and the other a membership" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(referenceDate)
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(contributor)), AccountWithSubscriptions(accountObjectWithZeroBalance, List(membership))),  paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return attributes when the membership is a friend tier" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Friend"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(friend))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return alertAvailableFor=membership for an active membership in payment failure" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate),
        AlertAvailableFor = Some("membership")
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithBalance, List(membership))), paymentMethodResponseRecentFailure, referenceDate)
      result must be_==(expected).await
    }
    "return alertAvailableFor=contribution for an active recurring contribution in payment failure" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None,
        AlertAvailableFor = Some("contribution")
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithBalance, List(contributor))), paymentMethodResponseRecentFailure, referenceDate)
      result must be_==(expected).await
    }

    "return alertAvailableFor=contribution when there is an active contribution and membership, both in payment failure" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(referenceDate),
        AlertAvailableFor = Some("contribution")
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithBalance, List(membership)), AccountWithSubscriptions(accountObjectWithBalance, List(contributor))), paymentMethodResponseRecentFailure, referenceDate)
      result must be_==(expected).await
    }

    "still return alertAvailableFor=contribution when there is an active contribution and paper sub, both in payment failure" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None,
        PaperSubscriptionExpiryDate = Some(referenceDate + 1.year),
        GuardianWeeklySubscriptionExpiryDate = None,
        AlertAvailableFor = Some("contribution")
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithBalance, List(sunday)), AccountWithSubscriptions(accountObjectWithBalance, List(contributor))), paymentMethodResponseRecentFailure, referenceDate)
      result must be_==(expected).await
    }

    "still return alertAvailableFor=contribution when there is an active contribution and guardianWeekly sub, both in payment failure" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None,
        PaperSubscriptionExpiryDate = None,
        GuardianWeeklySubscriptionExpiryDate = Some(referenceDate + 1.year),
        AlertAvailableFor = Some("contribution")
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(AccountWithSubscriptions(accountObjectWithBalance, List(guardianWeekly)), AccountWithSubscriptions(accountObjectWithBalance, List(contributor))), paymentMethodResponseRecentFailure, referenceDate)
      result must be_==(expected).await
    }

  }

}

