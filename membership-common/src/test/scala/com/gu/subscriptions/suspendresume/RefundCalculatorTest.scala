package com.gu.subscriptions.suspendresume
import com.github.nscala_time.time.Imports._
import com.gu.lib.DateDSL._
import com.gu.memsub.Benefit._
import com.gu.memsub.BillingSchedule.{Bill, BillItem}
import com.gu.memsub._
import com.gu.subscriptions.suspendresume.RefundCalculator._
import com.gu.subscriptions.suspendresume.SuspensionService.holidayToDays
import org.specs2.execute.Result
import org.specs2.mutable.Specification

import scalaz.NonEmptyList
import scalaz.syntax.std.option._

class RefundCalculatorTest extends Specification {

  "Days to products mapper" should {

    "Map calendar days to their products" in {
      Result.foreach(
        Map(
          (19 Jul 2016) -> TuesdayPaper,
          (20 Jul 2016) -> WednesdayPaper,
          (21 Jul 2016) -> ThursdayPaper,
          (22 Jul 2016) -> FridayPaper,
          (23 Jul 2016) -> SaturdayPaper,
          (24 Jul 2016) -> SundayPaper,
          (25 Jul 2016) -> MondayPaper,
        ).toSeq,
      ) { case (date, product) =>
        dayToProduct(date) mustEqual product
      }
    }
  }

  "Daily to monthly price conversion" should {
    "Yield the same results as those advertised on Jellyfish" in {
      monthlyPriceToWeekly(26.96f) mustEqual 6.22f // voucher+
      monthlyPriceToWeekly(22.63f) mustEqual 5.22f
      monthlyPriceToWeekly(12.52f) mustEqual 2.89f

      monthlyPriceToWeekly(50.23f) mustEqual 11.59f // voucher
      monthlyPriceToWeekly(45.03f) mustEqual 10.39f
      monthlyPriceToWeekly(27.01f) mustEqual 6.23f

      monthlyPriceToWeekly(22.97f) mustEqual 5.30f // delivery+
      monthlyPriceToWeekly(18.63f) mustEqual 4.30f
      monthlyPriceToWeekly(8.52f) mustEqual 1.97f

      monthlyPriceToWeekly(46.77f) mustEqual 10.79f // delivery
      monthlyPriceToWeekly(39.83f) mustEqual 9.19f
      monthlyPriceToWeekly(20.07f) mustEqual 4.63f
    }
  }

  "Refund calculation" should {

    val discountedBillingSchedule = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Jul 2016,
          1.month,
          NonEmptyList(
            BillItem("Sunday Paper", Some(SundayPaper), 5.0f, 5.0f),
            BillItem("100% discount", None, -5.0f, 100.0f),
          ),
        ),
      ),
    )

    val mondayBillingSchedule = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Jul 2016,
          1.month,
          NonEmptyList(
            BillItem("M Paper", Some(MondayPaper), 10.0f, 10.0f),
          ),
        ),
      ),
    )

    val monthlyTwoDayBillingSchedule = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Jul 2016,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 20.0f, 20.0f),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
          ),
        ),
        Bill(
          1 Aug 2016,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 20.0f, 20.0f),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
          ),
        ),
      ),
    )

    val quarterlyTwoDayBillingSchedule = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Jul 2016,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 20.0f * 4, 20.0f * 4),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f * 4, 10.0f * 4),
          ),
        ),
        Bill(
          1 Oct 2016,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 20.0f * 12, 20.0f * 4),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f * 12, 10.0f * 4),
          ),
        ),
      ),
    )

    val annualTwoDayBillingSchedule = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Jul 2016,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 20.0f * 12, 20.0f * 12),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f * 12, 10.0f * 12),
          ),
        ),
        Bill(
          1 Jul 2017,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 20.0f * 12, 20.0f * 12),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f * 12, 10.0f * 12),
          ),
        ),
      ),
    )

    val proRatedMultiDayMigratedBillingSchedule = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Nov 2016,
          1.month,
          NonEmptyList(
            BillItem("Monday Paper", Some(MondayPaper), 0.0f, 0.0f),
            BillItem("Tuesday Paper", Some(TuesdayPaper), 0.0f, 0.0f),
            BillItem("Wednesday Paper", Some(WednesdayPaper), 0.0f, 0.0f),
            BillItem("Thursday Paper", Some(ThursdayPaper), 0.0f, 0.0f),
            BillItem("Friday Paper", Some(FridayPaper), 2.95f, 7.37f),
            BillItem("Saturday Paper", Some(SaturdayPaper), 4.51f, 11.27f),
            BillItem("Sunday Paper", Some(SundayPaper), 0.0f, 0.0f),
          ),
        ),
      ),
    )

    "Give you nothing if you give no days" in {
      calculateRefund(Seq.empty, monthlyTwoDayBillingSchedule) mustEqual None
    }

    "Return None if you're not getting the paper on any of the days you're on holiday for" in {
      calculateRefund(Seq(19 Jul 2016, 20 Jul 2016), mondayBillingSchedule) mustEqual None
    }

    "Return None if we don't have a bill for the day you're trying to take on holiday" in {
      calculateRefund(Seq(5 Dec 2016), mondayBillingSchedule) mustEqual None
    }

    "Return a refund of zero if you do get the paper on the days you're on holiday for but don't pay anything" in {
      calculateRefund(Seq(24 Jul 2016), discountedBillingSchedule) mustEqual 0.0f.some
    }

    "Refund you the daily price if you do hit one of the days" in {
      calculateRefund(Seq(25 Jul 2016), mondayBillingSchedule) mustEqual monthlyPriceToWeekly(10f).some
      calculateRefund(holidayToDays(25 Jul 2016, 27 Jul 2016), mondayBillingSchedule) mustEqual monthlyPriceToWeekly(10f).some
    }

    "Correctly calculate refund for two days" in {
      "with a monthly billing period" in {
        calculateRefund(Seq(25 Jul 2016, 26 Jul 2016), monthlyTwoDayBillingSchedule) mustEqual monthlyPriceToWeekly(30f).some
      }

      "with a quarterly billing period" in {
        calculateRefund(Seq(25 Jul 2016, 26 Jul 2016), quarterlyTwoDayBillingSchedule) mustEqual quarterlyPriceToWeekly(30f * 4).some
      }

      "with an annual billing period" in {
        calculateRefund(Seq(25 Jul 2016, 26 Jul 2016), annualTwoDayBillingSchedule) mustEqual annualPriceToWeekly(30f * 12).some
      }
    }
    "Correctly calculate refund for 2-day multi-day spanning 2 weeks in pro-rated last month - prod issue test case" in {
      // Refund should be £8.60. (Friday @ £7.37 per month * (12 / 52) * 2 days)  + (Saturday @ £11.27 per month * (12 / 52) * 2 days)
      calculateRefund(holidayToDays(4 Nov 2016, 12 Nov 2016), proRatedMultiDayMigratedBillingSchedule) mustEqual 8.60f.some
    }

    "Work correctly with step up discounts" in {

      val stepUpBillingSchedule = BillingSchedule(
        NonEmptyList(
          Bill(
            1 Jul 2016,
            1.month,
            NonEmptyList(
              BillItem("Monday Paper", Some(MondayPaper), 20.0f, 20.0f),
              BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
              BillItem("Discount 50%", None, -15.0f, 50.0f),
            ),
          ),
          Bill(
            1 Aug 2016,
            1.month,
            NonEmptyList(
              BillItem("Monday Paper", Some(MondayPaper), 20.0f, 20.0f),
              BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
            ),
          ),
        ),
      )

      calculateRefund(Seq(1 Aug 2016), stepUpBillingSchedule) mustEqual monthlyPriceToWeekly(20.0f).some
      calculateRefund(Seq(11 Jul 2016), stepUpBillingSchedule) mustEqual (monthlyPriceToWeekly(20.0f) / 2).some
    }

    "Ignore any invoicing adjustments, e.g. Holiday Credits, when calculating discount" in {

      val stepUpBillingSchedule = BillingSchedule(
        NonEmptyList(
          Bill(
            1 Jul 2016,
            1.month,
            NonEmptyList(
              BillItem("Monday Paper", Some(MondayPaper), 20.0f, 20.0f),
              BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 20.0f),
              BillItem("Holiday Credit", Adjustment.some, -2.20f, -2.20f),
              BillItem("Adjustment charge", None, -2.20f, -2.20f), // Echo-Legacy Percent Discount adjustment (has no Benefit)
            ),
          ),
          Bill(
            1 Aug 2016,
            1.month,
            NonEmptyList(
              BillItem("Monday Paper", Some(MondayPaper), 20.0f, 20.0f),
              BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
              BillItem("Pro-rated earlier failed payment", Adjustment.some, 1.00f, 1.00f),
              BillItem("Adjustment charge", None, 1.00f, 1.00f), // Echo-Legacy Percent Discount adjustment (has no Benefit)
            ),
          ),
        ),
      )

      calculateRefund(Seq(1 Aug 2016), stepUpBillingSchedule) mustEqual monthlyPriceToWeekly(20.0f).some
      calculateRefund(Seq(11 Jul 2016), stepUpBillingSchedule) mustEqual monthlyPriceToWeekly(20.0f).some
    }

    "Treat invoice items with a gross of zero to mean that you're not getting the paper" in {

      // customers on the old pick-which-days-you-want scheme
      val legacySchedule = BillingSchedule(
        NonEmptyList(
          Bill(
            1 Jul 2016,
            1.month,
            NonEmptyList(
              BillItem("Monday Paper", Some(MondayPaper), 0.0f, 0.0f),
              BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
              BillItem("Wednesday Paper", Some(WednesdayPaper), 0.0f, 0.0f),
              BillItem("Thursday Paper", Some(ThursdayPaper), 10.0f, 10.0f),
              BillItem("Friday Paper", Some(FridayPaper), 0.0f, 0.0f),
              BillItem("Saturday Paper", Some(SaturdayPaper), 0.0f, 0.0f),
              BillItem("Sunday Paper", Some(SundayPaper), 10.0f, 10.0f),
            ),
          ),
        ),
      )

      calculateRefund(Seq(13 Jul 2016), legacySchedule) mustEqual None
      calculateRefund(Seq(12 Jul 2016), legacySchedule) mustEqual monthlyPriceToWeekly(10.0f).some
      calculateRefund(Seq(12 Jul 2016, 13 Jul 2016), legacySchedule) mustEqual monthlyPriceToWeekly(10.0f).some
    }

    "Ensure floating points are rounded appropriately when summing charges, and a 0 total is not None - Prod issue" in {

      // Migrated customer with a pro-ration charge in their first month
      val legacySchedule = BillingSchedule(
        NonEmptyList(
          Bill(
            1 Jul 2016,
            1.month,
            NonEmptyList(
              BillItem("Fixed Discount", None, -20.74f, -59.71f),
              BillItem("Monday Paper - prorated", Some(MondayPaper), 2.7f, 7.37f),
              BillItem("Tuesday Paper - prorated", Some(TuesdayPaper), 2.7f, 7.37f),
              BillItem("Wednesday Paper - prorated", Some(WednesdayPaper), 2.7f, 7.37f),
              BillItem("Thursday Paper - prorated", Some(ThursdayPaper), 2.7f, 7.37f),
              BillItem("Friday Paper - prorated", Some(FridayPaper), 2.7f, 7.37f),
              BillItem("Saturday Paper - prorated", Some(SaturdayPaper), 3.57f, 11.27f),
              BillItem("Sunday Paper - prorated", Some(SundayPaper), 3.67f, 11.59f),
            ),
          ),
        ),
      )

      calculateRefund(Seq(6 Jun 2016), legacySchedule) mustEqual 0.0f.some // next month lookup
      calculateRefund(Seq(4 Jul 2016), legacySchedule) mustEqual 0.0f.some
      calculateRefund(Seq(4 Jul 2016), legacySchedule) mustEqual 0.0f.some
      calculateRefund(holidayToDays(1 Jul 2016, 30 Jul 2016), legacySchedule) mustEqual 0.0f.some

      //
      calculateRefund(Seq(2 May 2016), legacySchedule) mustEqual 0.0f.some // next quarter lookup
      calculateRefund(Seq(1 Jul 2015), legacySchedule) mustEqual 0.0f.some // next year lookup
    }

    "Tries using the subsequent month's invoice if it can't find a corresponding charge in the invoice covering the passed day" in {
      // This covers Echo-Legacy migrated subs who have adjustments in their first billing period after import.

      val legacySchedule = BillingSchedule(
        NonEmptyList(
          Bill(
            1 Jul 2016,
            1.month,
            NonEmptyList(
              BillItem("Adjustment charge", None, 2.00f, 2.00f),
              BillItem("Monday Paper", Some(MondayPaper), 5.0f, 5.0f),
            ),
          ),
          Bill(
            1 Aug 2016,
            1.month,
            NonEmptyList(
              BillItem("Adjustment charge", None, 0.50f, 0.50f),
              BillItem("Tuesday Paper", Some(TuesdayPaper), 10.0f, 10.0f),
            ),
          ),
          Bill(
            1 Sep 2016,
            1.month,
            NonEmptyList(
              BillItem("Tuesday Paper", Some(TuesdayPaper), 20.0f, 20.0f),
            ),
          ),
        ),
      )

      calculateRefund(Seq(12 Jul 2016), legacySchedule) mustEqual monthlyPriceToWeekly(10.0f).some
    }
  }
}
