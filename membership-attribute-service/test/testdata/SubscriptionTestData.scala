package testdata

import com.github.nscala_time.time.Implicits._
import com.gu.i18n.Currency.GBP
import com.gu.memsub.Subscription._
import com.gu.memsub.subsv2.ReaderType.Gift
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.services.TestCatalog
import com.gu.memsub.{Subscription => _, _}
import org.joda.time.LocalDate
import scalaz.NonEmptyList

trait SubscriptionTestData {

  def referenceDate: LocalDate

  def supporterPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
    RatePlan(
      RatePlanId("idSupporter"),
      TestCatalog.supporterPrpId,
      "Supporter",
      None,
      List.empty,
      None,
      NonEmptyList(
        RatePlanCharge(
          SubscriptionRatePlanChargeId("nar"),
          ProductRatePlanChargeId("bar"),
          PricingSummary(Map(GBP -> Price(49.0f, GBP))),
          Some(ZYear),
          None,
          SubscriptionEnd,
          None,
          None,
        ),
      ),
      startDate,
      endDate,
    )
  def digipackPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
    RatePlan(
      RatePlanId("idDigipack"),
      TestCatalog.digipackPrpId,
      "Digital Pack",
      None,
      List.empty,
      None,
      NonEmptyList(
        RatePlanCharge(
          SubscriptionRatePlanChargeId("naz"),
          ProductRatePlanChargeId("baz"),
          PricingSummary(Map(GBP -> Price(119.90f, GBP))),
          Some(ZYear),
          None,
          SubscriptionEnd,
          None,
          None,
        ),
      ),
      startDate,
      endDate,
    )
  def paperPlan(startDate: LocalDate, endDate: LocalDate): RatePlan = RatePlan(
    RatePlanId("idPaperPlan"),
    TestCatalog.homeDeliveryPrpId,
    "Sunday",
    None,
    List.empty,
    None,
    NonEmptyList(
      RatePlanCharge(
        SubscriptionRatePlanChargeId("sunpaper_rpcid"),
        ProductRatePlanChargeId("sunPaper_prpcid"),
        PricingSummary(Map(GBP -> Price(5.07f, GBP))),
        Some(ZQuarter),
        None,
        SubscriptionEnd,
        None,
        None,
      ),
    ),
    startDate,
    endDate,
  )
  def paperPlusPlan(startDate: LocalDate, endDate: LocalDate): RatePlan = RatePlan(
    RatePlanId("idPaperPlusPlan"),
    ProductRatePlanId("prpi"),
    "Sunday+",
    None,
    List.empty,
    None,
    NonEmptyList(
      RatePlanCharge(
        SubscriptionRatePlanChargeId("digi_rpcid"),
        ProductRatePlanChargeId("digi_prpcid"),
        PricingSummary(Map(GBP -> Price(5.07f, GBP))),
        Some(ZQuarter),
        None,
        SubscriptionEnd,
        None,
        None,
      ),
      RatePlanCharge(
        SubscriptionRatePlanChargeId("sunpaper_rpcid"),
        ProductRatePlanChargeId("sunPaper_prpcid"),
        PricingSummary(Map(GBP -> Price(119.90f, GBP))),
        Some(ZQuarter),
        None,
        SubscriptionEnd,
        None,
        None,
      ),
    ),
    startDate,
    endDate,
  )
  def contributorPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
    RatePlan(
      RatePlanId("idContributor"),
      TestCatalog.contributorPrpId,
      "Monthly Contribution",
      None,
      List.empty,
      None,
      NonEmptyList(
        RatePlanCharge(
          SubscriptionRatePlanChargeId("nar"),
          ProductRatePlanChargeId("bar"),
          PricingSummary(Map(GBP -> Price(5.0f, GBP))),
          Some(ZMonth),
          None,
          SubscriptionEnd,
          None,
          None,
        ),
      ),
      startDate,
      endDate,
    )
  def guardianWeeklyPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
    RatePlan(
      RatePlanId("idGuardianWeeklyPlan"),
      TestCatalog.gw,
      "Guardian Weekly",
      None,
      List.empty,
      None,
      NonEmptyList(
        RatePlanCharge(
          SubscriptionRatePlanChargeId("nar"),
          ProductRatePlanChargeId("bar"),
          PricingSummary(Map(GBP -> Price(37.50f, GBP))),
          Some(ZQuarter),
          None,
          SubscriptionEnd,
          None,
          None,
        ),
      ),
      startDate,
      endDate,
    )

  def supporterPlusPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
    RatePlan(
      RatePlanId("idSupporterPlusPlan"),
      TestCatalog.supporterPlusPrpId,
      "Supporter Plus",
      None,
      List.empty,
      None,
      NonEmptyList(
        RatePlanCharge(
          SubscriptionRatePlanChargeId("nar"),
          ProductRatePlanChargeId("bar"),
          PricingSummary(Map(GBP -> Price(10.0f, GBP))),
          Some(ZMonth),
          None,
          SubscriptionEnd,
          None,
          None,
        ),
      ),
      startDate,
      endDate,
    )

  def toSubscription(isCancelled: Boolean)(plans: NonEmptyList[RatePlan]): Subscription = {
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
      ratePlans = plans.list.toList,
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

  val catalog = TestCatalog.catalog
}
