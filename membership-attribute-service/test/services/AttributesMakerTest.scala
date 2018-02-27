package services

import com.github.nscala_time.time.Implicits._
import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.rest.ZuoraRestService.{AccountSummary, BillToContact, DefaultPaymentMethod, PaymentMethodId, PaymentMethodResponse, SoldToContact}
import models.{Attributes, CustomerAccount, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import testdata.SubscriptionTestData
import testdata.AccountSummaryTestData._

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
    val anotherAccountSummary = accountSummaryWithZeroBalance.copy(id = AccountId("another accountId"))

    "return attributes when digipack sub" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(digipack))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return attributes when one of the subs has a digital benefit" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(sunday)), CustomerAccount(anotherAccountSummary, Some(sundayPlus))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return none when only sub is expired" in {
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(expiredMembership))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(None).await
    }

    "return attributes when there is one membership sub" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(membership))), paymentMethodResponseNoFailures, referenceDate)
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

      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(expiredMembership)), CustomerAccount(accountSummaryWithZeroBalance, Some(contributor))), paymentMethodResponseNoFailures, referenceDate)
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
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(contributor))), paymentMethodResponseNoFailures, referenceDate)
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
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(contributor)), CustomerAccount(accountSummaryWithZeroBalance, Some(membership))),  paymentMethodResponseNoFailures, referenceDate)
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
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithZeroBalance, Some(friend))), paymentMethodResponseNoFailures, referenceDate)
      result must be_==(expected).await
    }

    "return actionAvailableFor=membership for an active membership in payment failure" in {
      val expected = Some(ZuoraAttributes(
        UserId = identityId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate),
        ActionAvailableFor = Some("membership")
      )
      )
      val result = AttributesMaker.zuoraAttributes(identityId, List(CustomerAccount(accountSummaryWithBalance, Some(membership))), paymentMethodResponseRecentFailure, referenceDate)
      result must be_==(expected).await
    }
  }


  "attributes" should {
    "return up to date Zuora attributes when they match the dynamo attributes" in {
      val zuoraAttributes = ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = None
      )

      val dynamoAttributes = DynamoAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = None,
        MembershipNumber = None,
        AdFree = None,
        TTLTimestamp = referenceDateAsDynamoTimestamp
      )

      val expected = Some(
        Attributes(
          UserId = identityId,
          Tier = None,
          RecurringContributionPaymentPlan = Some("Monthly Contribution"),
          MembershipJoinDate = None,
          DigitalSubscriptionExpiryDate = None
        )
      )

      val attributes = AttributesMaker.zuoraAttributesWithAddedDynamoFields(Some(zuoraAttributes), Some(dynamoAttributes))

      attributes === expected
    }

    "still return Zuora attributes when the user is not in Dynamo" in {
      val zuoraAttributes = ZuoraAttributes(
        UserId = identityId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = None
      )

      val expected = Some(
        Attributes(
          UserId = identityId,
          Tier = None,
          RecurringContributionPaymentPlan = Some("Monthly Contribution"),
          MembershipJoinDate = None,
          DigitalSubscriptionExpiryDate = None
        )
      )

      val attributes = AttributesMaker.zuoraAttributesWithAddedDynamoFields(Some(zuoraAttributes), None)

      attributes === expected
    }

    "return none if both Dynamo and Zuora attributes are none" in {
      AttributesMaker.zuoraAttributesWithAddedDynamoFields(None, None) === None
    }

  }

  "actionAvailableFor" should {

    "return false for a member with a zero balance" in {
      val result = AttributesMaker.actionAvailableFor(accountSummaryWithZeroBalance, membership, paymentMethodResponseNoFailures)

      result must be_==(false).await
    }

    "return false for a member with a failed payment more than 27 days ago" in {
      val result = AttributesMaker.actionAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponseStaleFailure)

      result must be_==(false).await
    }

    "return false for a member with a balance but no failed payments" in {
      val result = AttributesMaker.actionAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponseNoFailures)

      result must be_==(false).await
    }

    "return true for for a member with a failed payment in the last 27 days" in {
      val result = AttributesMaker.actionAvailableFor(accountSummaryWithBalance, membership, paymentMethodResponseRecentFailure)

      result must be_==(true).await
    }

    "return true for for a non-membership sub with a failed payment in the last 27 days too" in {
      val result = AttributesMaker.actionAvailableFor(accountSummaryWithBalance, digipack, paymentMethodResponseRecentFailure)

      result must be_==(true).await
    }

  }

}

