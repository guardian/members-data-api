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

  def supporterPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan =
    SubscriptionPlan(
      RatePlanId("idSupporter"),
      ProductRatePlanId("prpi"),
      "Supporter",
      "desc",
      "Supporter",
      None,
      "Membership",
      Product.Membership,
      List.empty,
      SingleCharge(
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
  def digipackPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan =
    SubscriptionPlan(
      RatePlanId("idDigipack"),
      ProductRatePlanId("prpi"),
      "Digipack",
      "desc",
      "Digital Pack",
      None,
      "Digital Pack",
      Product.Digipack,
      List.empty,
      SingleCharge(
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
  def paperPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan = SubscriptionPlan(
    RatePlanId("idPaperPlan"),
    ProductRatePlanId("prpi"),
    "Sunday",
    "desc",
    "Sunday",
    None,
    "Newspaper - Home Delivery",
    Product.Delivery,
    List.empty,
    PaperCharges(Seq((SundayPaper, PricingSummary(Map(GBP -> Price(5.07f, GBP))))).toMap, None),
    None,
    startDate,
    endDate,
  )
  def paperPlusPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan = SubscriptionPlan(
    RatePlanId("idPaperPlusPlan"),
    ProductRatePlanId("prpi"),
    "Sunday+",
    "desc",
    "Sunday+",
    None,
    "Newspaper - Home Delivery",
    Product.Delivery,
    List.empty,
    PaperCharges(Seq((SundayPaper, PricingSummary(Map(GBP -> Price(5.07f, GBP))))).toMap, Some(PricingSummary(Map(GBP -> Price(119.90f, GBP))))),
    None,
    startDate,
    endDate,
  )
  def contributorPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan =
    SubscriptionPlan(
      RatePlanId("idContributor"),
      ProductRatePlanId("prpi"),
      "Monthly Contribution",
      "desc",
      "Monthly Contribution",
      None,
      "Contribution",
      Product.Contribution,
      List.empty,
      SingleCharge(
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
  def guardianWeeklyPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan =
    SubscriptionPlan(
      RatePlanId("idGuardianWeeklyPlan"),
      ProductRatePlanId("prpi"),
      "Guardian Weekly",
      "desc",
      "Guardian Weekly",
      None,
      "Guardian Weekly",
      Product.WeeklyDomestic,
      List.empty,
      SingleCharge(
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

  def supporterPlusPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan =
    SubscriptionPlan(
      RatePlanId("idSupporterPlusPlan"),
      ProductRatePlanId("prpi"),
      "Supporter Plus",
      "desc",
      "Supporter Plus",
      None,
      "Supporter Plus",
      Product.SupporterPlus,
      List.empty,
      SingleCharge(
        Benefit.SupporterPlus,
        BillingPeriod.Month,
        PricingSummary(Map(GBP -> Price(10.0f, GBP))),
        ProductRatePlanChargeId("bar"),
        SubscriptionRatePlanChargeId("nar"),
      ),
      None,
      startDate,
      endDate,
    )

  def toSubscription(isCancelled: Boolean)(plans: NonEmptyList[SubscriptionPlan]): Subscription = {
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
  val contributor = toSubscription(false)(NonEmptyList(contributorPlan(referenceDate, referenceDate + 1.month)))
  val supporterPlus = toSubscription(false)(NonEmptyList(supporterPlusPlan(referenceDate, referenceDate + 1.month)))
}
