package testdata

import com.github.nscala_time.time.Implicits._
import com.gu.i18n.Currency.GBP
import com.gu.memsub.Benefit._
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId, RatePlanId, _}
import com.gu.memsub.subsv2.ReaderType.Gift
import com.gu.memsub.subsv2._
import com.gu.memsub.{Subscription => _, _}
import org.joda.time.LocalDate

import scalaz.NonEmptyList

trait SubscriptionTestData {

  def referenceDate: LocalDate

  val friendPlan = FreeSubscriptionPlan[Product.Membership, FreeCharge[Benefit.Friend.type]](
    RatePlanId("idFriend"),
    ProductRatePlanId("prpi"),
    "Friend",
    "desc",
    "Friend",
    "Membership",
    Product.Membership,
    FreeCharge(Friend, Set(GBP)),
    referenceDate,
    referenceDate + 1.year,
  )
  def supporterPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Supporter =
    PaidSubscriptionPlan[Product.Membership, PaidCharge[Benefit.Supporter.type, BillingPeriod]](
      RatePlanId("idSupporter"),
      ProductRatePlanId("prpi"),
      "Supporter",
      "desc",
      "Supporter",
      "Membership",
      Product.Membership,
      List.empty,
      PaidCharge(
        Supporter,
        BillingPeriod.Year,
        PricingSummary(Map(GBP -> Price(49.0f, GBP))),
        ProductRatePlanChargeId("bar"),
        SubscriptionRatePlanChargeId("nar"),
      ),
      None,
      startDate,
      endDate,
    )
  def digipackPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Digipack =
    PaidSubscriptionPlan[Product.Digipack, PaidCharge[Benefit.Digipack.type, BillingPeriod]](
      RatePlanId("idDigipack"),
      ProductRatePlanId("prpi"),
      "Digipack",
      "desc",
      "Digital Pack",
      "Digital Pack",
      Product.Digipack,
      List.empty,
      PaidCharge(
        Digipack,
        BillingPeriod.Year,
        PricingSummary(Map(GBP -> Price(119.90f, GBP))),
        ProductRatePlanChargeId("baz"),
        SubscriptionRatePlanChargeId("naz"),
      ),
      None,
      startDate,
      endDate,
    )
  def paperPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Delivery = PaidSubscriptionPlan[Product.Delivery, PaperCharges](
    RatePlanId("idPaperPlan"),
    ProductRatePlanId("prpi"),
    "Sunday",
    "desc",
    "Sunday",
    "Newspaper - Home Delivery",
    Product.Delivery,
    List.empty,
    PaperCharges(Seq((SundayPaper, PricingSummary(Map(GBP -> Price(5.07f, GBP))))).toMap, None),
    None,
    startDate,
    endDate,
  )
  def paperPlusPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Delivery = PaidSubscriptionPlan[Product.Delivery, PaperCharges](
    RatePlanId("idPaperPlusPlan"),
    ProductRatePlanId("prpi"),
    "Sunday+",
    "desc",
    "Sunday+",
    "Newspaper - Home Delivery",
    Product.Delivery,
    List.empty,
    PaperCharges(Seq((SundayPaper, PricingSummary(Map(GBP -> Price(5.07f, GBP))))).toMap, Some(PricingSummary(Map(GBP -> Price(119.90f, GBP))))),
    None,
    startDate,
    endDate,
  )
  def contributorPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Contributor =
    PaidSubscriptionPlan[Product.Contribution, PaidCharge[Benefit.Contributor.type, BillingPeriod]](
      RatePlanId("idContributor"),
      ProductRatePlanId("prpi"),
      "Monthly Contribution",
      "desc",
      "Monthly Contribution",
      "Contribution",
      Product.Contribution,
      List.empty,
      PaidCharge(
        Contributor,
        BillingPeriod.Month,
        PricingSummary(Map(GBP -> Price(5.0f, GBP))),
        ProductRatePlanChargeId("bar"),
        SubscriptionRatePlanChargeId("nar"),
      ),
      None,
      startDate,
      endDate,
    )
  def guardianWeeklyPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.WeeklyPlan =
    PaidSubscriptionPlan[Product.WeeklyDomestic, PaidCharge[Benefit.Weekly.type, BillingPeriod]](
      RatePlanId("idGuardianWeeklyPlan"),
      ProductRatePlanId("prpi"),
      "Guardian Weekly",
      "desc",
      "Guardian Weekly",
      "Guardian Weekly",
      Product.WeeklyDomestic,
      List.empty,
      PaidCharge(
        Weekly,
        BillingPeriod.Quarter,
        PricingSummary(Map(GBP -> Price(37.50f, GBP))),
        ProductRatePlanChargeId("bar"),
        SubscriptionRatePlanChargeId("nar"),
      ),
      None,
      startDate,
      endDate,
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
      plans = CovariantNonEmptyList(plans.head, plans.tail.toList),
      readerType = ReaderType.Direct,
      gifteeIdentityId = None,
      autoRenew = true,
    )
  }

  val digipack = toSubscription(false)(NonEmptyList(digipackPlan(referenceDate, referenceDate + 1.year)))
  val digipackGift = toSubscription(false)(NonEmptyList(digipackPlan(referenceDate, referenceDate + 1.year)))
    .copy(readerType = Gift, gifteeIdentityId = Some("12345"))
  val guardianWeekly = toSubscription(false)(NonEmptyList(guardianWeeklyPlan(referenceDate, referenceDate + 1.year)))
  val sunday = toSubscription(false)(NonEmptyList(paperPlan(referenceDate, referenceDate + 1.year)))
  val sundayPlus = toSubscription(false)(NonEmptyList(paperPlusPlan(referenceDate, referenceDate + 1.year)))
  val membership = toSubscription(false)(NonEmptyList(supporterPlan(referenceDate, referenceDate + 1.year)))
  val cancelledMembership = toSubscription(true)(NonEmptyList(supporterPlan(referenceDate, referenceDate + 1.year)))
  val expiredMembership = toSubscription(false)(NonEmptyList(supporterPlan(referenceDate - 2.year, referenceDate - 1.year)))
  val friend = toSubscription(false)(NonEmptyList(friendPlan))
  val contributor = toSubscription(false)(NonEmptyList(contributorPlan(referenceDate, referenceDate + 1.month)))
}
