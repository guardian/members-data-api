package com.gu.memsub.subsv2.reads

import com.gu.Diff
import Diff._
import com.gu.i18n.Currency._
import com.gu.memsub.Benefit._
import com.gu.memsub.BillingPeriod.Month
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, SubscriptionRatePlanChargeId}
import com.gu.memsub._
import com.gu.memsub.subsv2.{ChargeList, _}
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.softwaremill.diffx
import com.softwaremill.diffx._
import com.softwaremill.diffx.{Derived, Diff}
import com.softwaremill.diffx.generic.auto._
import com.softwaremill.diffx.scalatest.DiffShouldMatcher._
import org.scalatest.flatspec.AnyFlatSpec
import scalaz.{Failure, NonEmptyList, Success, ValidationNel}

class ChargeListReadsTest extends AnyFlatSpec {

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

  "product reads" should "read any supporter as any benefit successfully" in {

    val result: ValidationNel[String, Benefit] = implicitly[ChargeReads[Benefit]].read(planChargeMap, supporterCharge)

    val expected: ValidationNel[String, Benefit] = Success(Supporter)

    result shouldMatchTo expected

  }

  "product reads" should "read any supporter as a supporter successfully" in {

    val result: ValidationNel[String, Benefit.Supporter.type] = implicitly[ChargeReads[Supporter.type]].read(planChargeMap, supporterCharge)

    val expected: ValidationNel[String, Benefit.Supporter.type] = Success(Supporter)

    result shouldMatchTo expected

  }

  "product reads" should "not read any supporter as a weekly" in {

    val result: ValidationNel[String, Benefit.Weekly.type] = implicitly[ChargeReads[Weekly.type]].read(planChargeMap, supporterCharge)

    val expected: ValidationNel[String, Benefit.Weekly.type] =
      Failure(NonEmptyList("expected class com.gu.memsub.Benefit$Weekly$ but was Supporter (isPhysical? = true)"))

    result.leftMap(_.list) shouldMatchTo (expected.leftMap(_.list))

  }

  "product reads" should "read any supporter as a member successfully" in {

    val result: ValidationNel[String, MemberTier] = implicitly[ChargeReads[MemberTier]].read(planChargeMap, supporterCharge)

    val expected: ValidationNel[String, Supporter.type] = Success(Supporter)

    result.map(_.asInstanceOf[Supporter.type]) shouldMatchTo expected

  }

  "ChargeList reads" should "read single-charge non-paper rate plans as generic PaidCharge type" in {
    val result: ValidationNel[String, ChargeList] = readChargeList.read(planChargeMap, List(weeklyCharge))

    val expected: ValidationNel[String, ChargeList] =
      Success(SingleCharge(Weekly, Month, weeklyCharge.pricing, weeklyCharge.productRatePlanChargeId, weeklyCharge.id))

    result shouldMatchTo expected

    // Instance type check for extra proof
    assert(!result.exists(_.isInstanceOf[PaperCharges]))
  }

  "ChargeList reads" should "read single-charge paper rate plans as PaperCharges type" in {

    val sundayPrices = Seq((SundayPaper, paperCharge.pricing)).toMap[PaperDay, PricingSummary]

    val result: ValidationNel[String, ChargeList] = readChargeList.read(planChargeMap, List(paperCharge))

    val expected: ValidationNel[String, ChargeList] = Success(PaperCharges(dayPrices = sundayPrices, digipack = None))

    result shouldMatchTo expected

    // Instance type check for extra proof
    assert(result.exists(_.isInstanceOf[PaperCharges]))
  }

  "ChargeList reads" should "not confuse digipack rate plan with paper+digital plan" in {
    val result: ValidationNel[String, ChargeList] = readChargeList.read(planChargeMap, List(digipackCharge))

    val expected: ValidationNel[String, ChargeList] =
      Success(SingleCharge(Digipack, Month, digipackCharge.pricing, digipackCharge.productRatePlanChargeId, digipackCharge.id))

    result shouldMatchTo expected

    // Instance type check for extra proof
    assert(!result.exists(_.isInstanceOf[PaperCharges]))
  }

}
