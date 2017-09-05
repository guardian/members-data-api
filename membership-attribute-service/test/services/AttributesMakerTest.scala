package services

import com.gu.i18n.Currency.GBP
import com.gu.memsub.Benefit._
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId, RatePlanId}
import com.github.nscala_time.time.Implicits._
import com.gu.memsub.{Product, Benefit, BillingPeriod, PricingSummary, Price}
import com.gu.memsub.Subscription._
import com.gu.memsub.subsv2._
import models.Attributes
import org.joda.time.LocalDate
import org.specs2.mutable.Specification

import scalaz.NonEmptyList

class AttributesMakerTest extends Specification {

  "attributes" should {
    val referenceDate = new LocalDate(2016, 10, 26)

    val friendPlan = FreeSubscriptionPlan[Product.Membership, FreeCharge[Benefit.Friend.type]](
      RatePlanId("idFriend"), ProductRatePlanId("prpi"), "Friend", "desc", "Friend", Product.Membership,FreeCharge(Friend, Set(GBP)), referenceDate
    )
    def supporterPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Supporter = PaidSubscriptionPlan[Product.Membership, PaidCharge[Benefit.Supporter.type, BillingPeriod]](
      RatePlanId("idSupporter"), ProductRatePlanId("prpi"), "Supporter", "desc", "Supporter", Product.Membership, List.empty, PaidCharge(Supporter, BillingPeriod.Year, PricingSummary(Map(GBP -> Price(49.0f, GBP))), ProductRatePlanChargeId("bar")), None, startDate, endDate
    )
    def digipackPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Digipack = PaidSubscriptionPlan[Product.ZDigipack, PaidCharge[Benefit.Digipack.type, BillingPeriod]](
      RatePlanId("idDigipack"), ProductRatePlanId("prpi"), "Digipack", "desc", "Digital Pack", Product.Digipack, List.empty, PaidCharge(Digipack, BillingPeriod.Year, PricingSummary(Map(GBP -> Price(119.90f, GBP))), ProductRatePlanChargeId("baz")), None, startDate, endDate
    )
    def paperPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Delivery = PaidSubscriptionPlan[Product.Delivery, PaperCharges](
      RatePlanId("idDigipack"), ProductRatePlanId("prpi"), "Sunday", "desc", "Sunday", Product.Delivery, List.empty, PaperCharges(Seq((SundayPaper, PricingSummary(Map(GBP -> Price(5.07f, GBP))))).toMap, None), None, startDate, endDate
    )
    def paperPlusPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Delivery = PaidSubscriptionPlan[Product.Delivery, PaperCharges](
      RatePlanId("idDigipack"), ProductRatePlanId("prpi"), "Sunday+", "desc", "Sunday+", Product.Delivery, List.empty, PaperCharges(Seq((SundayPaper, PricingSummary(Map(GBP -> Price(5.07f, GBP))))).toMap, Some(PricingSummary(Map(GBP -> Price(119.90f, GBP))))), None, startDate, endDate
    )
    def contributorPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Contributor = PaidSubscriptionPlan[Product.Contribution, PaidCharge[Benefit.Contributor.type, BillingPeriod]](
      RatePlanId("idContributor"), ProductRatePlanId("prpi"), "Monthly Contribution", "desc", "Monthly Contribution", Product.Contribution, List.empty, PaidCharge(Contributor, BillingPeriod.Month, PricingSummary(Map(GBP -> Price(5.0f, GBP))), ProductRatePlanChargeId("bar")), None, startDate, endDate
    )

    def toSubscription[P <: SubscriptionPlan.AnyPlan](isCancelled: Boolean)(plans: NonEmptyList[P]): Subscription[P] = {
      Subscription(
        id = Id(plans.head.id.get),
        name = Name("AS-123123"),
        accountId = AccountId("accountId"),
        startDate = plans.head.start,
        acceptanceDate = plans.head.start,
        termStartDate = plans.head.start,
        termEndDate = plans.head.start + 1.year,
        casActivationDate = None,
        promoCode = None,
        isCancelled = isCancelled,
        hasPendingFreePlan = false,
        plans = plans,
        readerType = ReaderType.Direct,
        autoRenew = true
      )
    }

    val testId = "123"
    val digipack = toSubscription(false)(NonEmptyList(digipackPlan(referenceDate, referenceDate + 1.year)))
    val sunday = toSubscription(false)(NonEmptyList(paperPlan(referenceDate, referenceDate + 1.year)))
    val sundayPlus = toSubscription(false)(NonEmptyList(paperPlusPlan(referenceDate, referenceDate + 1.year)))
    val membership = toSubscription(false)(NonEmptyList(supporterPlan(referenceDate, referenceDate + 1.year)))
    val expiredMembership = toSubscription(false)(NonEmptyList(supporterPlan(referenceDate - 2.year, referenceDate - 1.year)))
    val friend = toSubscription(false)(NonEmptyList(friendPlan))
    val contributor = toSubscription(false)(NonEmptyList(contributorPlan(referenceDate, referenceDate + 1.month)))

    "return attributes when digipack sub" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = None,
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      AttributesMaker.attributes(testId, List(digipack), referenceDate) === expected
    }

    "return attributes when one of the subs has a digital benefit" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = None,
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      AttributesMaker.attributes(testId, List(sunday, sundayPlus), referenceDate) === expected
    }

    "return none when only sub is expired" in {
      AttributesMaker.attributes(testId, List(expiredMembership), referenceDate) === None
    }

    "return attributes when there is one membership sub" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Supporter"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.attributes(testId, List(membership), referenceDate) === expected
    }

    "return attributes when one sub is expired and one is not" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = None,
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None
      )
      )

      AttributesMaker.attributes(testId, List(expiredMembership, contributor), referenceDate) === expected
    }

    "return attributes when one sub is a recurring contribution" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = None,
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None
      )
      )
      AttributesMaker.attributes(testId, List(contributor), referenceDate) === expected
    }

    "return attributes relevant to both when one sub is a contribution and the other a membership" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Supporter"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.attributes(testId, List(contributor, membership), referenceDate) === expected
    }

    "return attributes when the membership is a friend tier" in {
      val expected = Some(Attributes(
        UserId = testId,
        Tier = Some("Friend"),
        MembershipNumber = None,
        AdFree = None,
        Wallet = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.attributes(testId, List(friend), referenceDate) === expected
    }
  }
}

