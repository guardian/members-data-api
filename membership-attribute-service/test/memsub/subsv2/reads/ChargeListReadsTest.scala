package memsub.subsv2.reads

import com.gu.i18n.Currency._
import models.subscription.Benefit.{Digipack, FreeMemberTier, MemberTier, PaperDay, SundayPaper, Supporter, Weekly}
import models.subscription.BillingPeriod.Month
import models.subscription.Subscription.ProductRatePlanChargeId
import models.subscription.subsv2.reads.{ChargeListReads, ChargeReads}
import models.subscription.subsv2.{PaidCharge, PaidChargeList, PaperCharges, SubscriptionEnd, ZMonth, ZuoraCharge}
import models.subscription.{Benefit, Price, PricingSummary}
import org.specs2.mutable.Specification
import scalaz.{Failure, NonEmptyList, Success, ValidationNel}

class ChargeListReadsTest extends Specification {
  import models.subscription.subsv2.reads.ChargeListReads._

  val planChargeMap = Map[ProductRatePlanChargeId, Benefit](
    ProductRatePlanChargeId("weekly") -> Weekly,
    ProductRatePlanChargeId("supporter") -> Supporter,
    ProductRatePlanChargeId("sunday") -> SundayPaper,
    ProductRatePlanChargeId("digipack") -> Digipack,
  )

  val supporterCharge = ZuoraCharge.apply(
    productRatePlanChargeId = ProductRatePlanChargeId("supporter"),
    pricing = PricingSummary(
      Map(
        GBP -> Price(11.99f, GBP),
      ),
    ),
    billingPeriod = Some(ZMonth),
    specificBillingPeriod = None,
    model = "FlatFee",
    name = "Supporter",
    `type` = "Recurring",
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
  )

  val weeklyCharge = ZuoraCharge.apply(
    productRatePlanChargeId = ProductRatePlanChargeId("weekly"),
    pricing = PricingSummary(
      Map(
        GBP -> Price(30.0f, GBP),
      ),
    ),
    billingPeriod = Some(ZMonth),
    specificBillingPeriod = None,
    model = "FlatFee",
    name = "Guardian Weekly Zone A",
    `type` = "Recurring",
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
  )

  val paperCharge = ZuoraCharge.apply(
    productRatePlanChargeId = ProductRatePlanChargeId("sunday"),
    pricing = PricingSummary(
      Map(
        GBP -> Price(15.12f, GBP),
      ),
    ),
    billingPeriod = Some(ZMonth),
    specificBillingPeriod = None,
    model = "FlatFee",
    name = "Sunday",
    `type` = "Recurring",
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
  )

  val digipackCharge = ZuoraCharge.apply(
    productRatePlanChargeId = ProductRatePlanChargeId("digipack"),
    pricing = PricingSummary(
      Map(
        GBP -> Price(11.99f, GBP),
      ),
    ),
    billingPeriod = Some(ZMonth),
    specificBillingPeriod = None,
    model = "FlatFee",
    name = "Digipack",
    `type` = "Recurring",
    endDateCondition = SubscriptionEnd,
    upToPeriods = None,
    upToPeriodsType = None,
  )

  "product reads" should {
    "read any supporter as any benefit successfully" in {

      val result: ValidationNel[String, Benefit] = implicitly[ChargeReads[Benefit]].read(planChargeMap, supporterCharge)

      val expected: ValidationNel[String, Benefit] = Success(Supporter)

      result mustEqual expected
    }

    "read any supporter as a supporter successfully" in {
      val result = implicitly[ChargeReads[Supporter.type]].read(planChargeMap, supporterCharge)

      val expected = Success(Supporter)

      result mustEqual expected
    }

    "not read any supporter as a weekly" in {
      val result = implicitly[ChargeReads[Weekly.type]].read(planChargeMap, supporterCharge)

      val expected = Failure(NonEmptyList("expected class models.subscription.Benefit$Weekly$ but was Supporter (isPhysical? = true)"))

      result.leftMap(_.list) mustEqual expected.leftMap(_.list)
    }

    "read any supporter as a member successfully" in {
      val result = implicitly[ChargeReads[MemberTier]].read(planChargeMap, supporterCharge)

      val expected = Success(Supporter)

      result mustEqual expected
    }

    "not read any supporter as a Free member" in {
      val result = implicitly[ChargeReads[FreeMemberTier]].read(planChargeMap, supporterCharge)

      val expected = Failure(NonEmptyList("expected interface models.subscription.Benefit$FreeMemberTier but was Supporter (isPhysical? = true)"))

      result.leftMap(_.list) mustEqual expected.leftMap(_.list)
    }
  }

  "ChargeList reads" should {
    "read single-charge non-paper rate plans as generic PaidCharge type" in {
      val result = implicitly[ChargeListReads[PaidChargeList]].read(planChargeMap, List(weeklyCharge))

      val expected = Success(PaidCharge(Weekly, Month, weeklyCharge.pricing, weeklyCharge.productRatePlanChargeId, weeklyCharge.id))

      result mustEqual expected

      // Instance type check for extra proof
      !result.exists(_.isInstanceOf[PaperCharges]) shouldEqual true
    }

    "read single-charge paper rate plans as PaperCharges type" in {
      val sundayPrices = Seq((SundayPaper, paperCharge.pricing)).toMap[PaperDay, PricingSummary]

      val result = implicitly[ChargeListReads[PaidChargeList]].read(planChargeMap, List(paperCharge))

      val expected = Success(PaperCharges(dayPrices = sundayPrices, digipack = None))

      result mustEqual expected

      // Instance type check for extra proof
      result.exists(_.isInstanceOf[PaperCharges]) shouldEqual true
    }

    "not confuse digipack rate plan with paper+digital plan" in {
      val result = implicitly[ChargeListReads[PaidChargeList]].read(planChargeMap, List(digipackCharge))

      val expected = Success(PaidCharge(Digipack, Month, digipackCharge.pricing, digipackCharge.productRatePlanChargeId, digipackCharge.id))

      result mustEqual expected

      // Instance type check for extra proof
      !result.exists(_.isInstanceOf[PaperCharges]) shouldEqual true
    }
  }
}
