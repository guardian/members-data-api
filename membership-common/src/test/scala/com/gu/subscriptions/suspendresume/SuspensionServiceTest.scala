package com.gu.subscriptions.suspendresume
import com.github.nscala_time.time.Imports._
import com.gu.config.HolidayRatePlanIds
import com.gu.lib.DateDSL._
import com.gu.memsub.Benefit._
import com.gu.memsub.BillingSchedule.{Bill, BillItem}
import com.gu.memsub.Subscription.{ProductRatePlanChargeId, ProductRatePlanId}
import com.gu.memsub._
import com.gu.subscriptions.suspendresume.JsonFormatters._
import com.gu.subscriptions.suspendresume.SuspensionService._
import com.gu.zuora.ZuoraRestConfig
import com.gu.zuora.rest.SimpleClient
import io.lemonlabs.uri.dsl._
import okhttp3._
import org.joda.time.LocalDate
import org.joda.time.LocalDate.now
import org.specs2.matcher.JsonMatchers
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import utils.Resource

import scalaz.Id._
import scalaz.WriterT._
import scalaz.std.list._
import scalaz.{NonEmptyList, Validation, Writer, \/}

class SuspensionServiceTest extends Specification with JsonMatchers {

  val subName = Subscription.Name("A-S1234")

  def response(contents: String) = new Response.Builder()
    .request(new Request.Builder().get().url("https://example.com/bar").build)
    .body(ResponseBody.create(MediaType.parse("application/json"), contents))
    .protocol(Protocol.HTTP_1_1)
    .message("test")
    .code(200)
    .build

  "Holiday to days" should {
    val day = 26 Mar 1982

    "Give you list of one day given the same start and end date" in {
      holidayToDays(day, day) mustEqual Seq(day)
    }
    "Give you list of two days given one day between the start and end date" in {
      holidayToDays(day, day + 1.day) mustEqual Seq(day, day + 1.day)
    }
    "Give you list of three days given two days between the start and end date" in {
      holidayToDays(day, day + 2.days) mustEqual Seq(day, day + 1.day, day + 2.days)
    }
    "Swap days behind the scenes if given the wrong way round" in {
      holidayToDays(day + 2.days, day) mustEqual Seq(day, day + 1.day, day + 2.days)
    }
  }
  "auto renew" should {
    "correctly parse zuora response" in {

      val json = Resource.getJson("rest/AmendmentResult.json")
      json.as[ZuoraResults] mustEqual ZuoraResults(Seq(ZuoraResult("1", 2.0, Seq("3"), 0.0, true)))

    }
  }

  "Suspension Holiday to suspended days" should {
    val start = 2 Aug 2016
    val finish = 2 Aug 2016
    val name = Subscription.Name("Anything")
    val holidays = Seq((0f, PaymentHoliday(name, start, finish)))

    "Be 0 when you're subscribed to no products" in {
      holidayToSuspendedDays(holidays, Seq.empty) mustEqual 0
    }
    "Be 1 when you're subscribed to one product which is inside the suspended day range" in {
      holidayToSuspendedDays(holidays, Seq(TuesdayPaper)) mustEqual 1
    }
    "Be 0 when you're subscribed to one product which is outside the suspended day range" in {
      holidayToSuspendedDays(holidays, Seq(WednesdayPaper)) mustEqual 0
    }
  }

  "Holiday runtime checking function" should {

    "Be dissatisfied with a negative number of days" in {
      val badDays = PaymentHoliday(subName, 1 Jan 2019, 31 Dec 2018)
      validateHoliday(badDays) mustEqual Validation.failureNel(NegativeDays)
    }

    "Enforce that the holiday must begin at least 2 days in the future" in {
      val today = 1 Jan 2016
      val lastMinuteBooking2 = PaymentHoliday(subName, 3 Jan 2016, 6 Jan 2016)
      validateHoliday(lastMinuteBooking2, today) mustEqual Validation.failureNel(NotEnoughNotice)
      val lastMinuteBooking3 = PaymentHoliday(subName, 4 Jan 2016, 6 Jan 2016)
      validateHoliday(lastMinuteBooking3, today).isSuccess mustEqual true
    }

    "Be happy with a zero number of days in range" in {
      val today = new LocalDate("2016-01-01")
      val oneDay = PaymentHoliday(subName, new LocalDate("2020-01-01"), new LocalDate("2020-01-01"))
      validateHoliday(oneDay, today).isSuccess mustEqual true
    }
  }

  "Amend from refund function that converts a Refund into a JSON object representing a Zuora update" should {

    val bs = BillingSchedule(
      NonEmptyList(
        Bill(
          30 Oct 2016,
          1.month,
          NonEmptyList(
            BillItem("Sunday Paper", Some(SundayPaper), 30.0f, 30.0f),
          ),
        ),
      ),
    )
    val plans = HolidayRatePlanIds(prpId = ProductRatePlanId("Skegness"), prpcId = ProductRatePlanChargeId("Is so bracing"))
    val json =
      amendFromRefund(plans).writes(HolidayRefundCommand((20, PaymentHoliday(subName, 1 Sep 2016, 14 Sep 2016)), 30, now.plusYears(1), bs)).toString

    "Set all four start dates(!) to the beginning of your holiday" in {
      json must /("add") /# 0 / ("contractEffectiveDate" -> "2016-09-01")
      json must /("add") /# 0 / ("serviceActivationDate" -> "2016-09-01")
      json must /("add") /# 0 / ("customerAcceptanceDate" -> "2016-09-01")
      json must /("add") /# 0 / "chargeOverrides" /# 0 / ("HolidayStart__c" -> "2016-09-01")
    }

    "Set HolidayEnd__c to the last day that you're on holiday (so suspensions are inclusive of the end date)" in {
      json must /("add") /# 0 / "chargeOverrides" /# 0 / ("HolidayEnd__c" -> "2016-09-14")
    }

    "Include the right Zuora IDs" in {
      json must /("add") /# 0 / ("productRatePlanId" -> "Skegness")
      json must /("add") /# 0 / "chargeOverrides" /# 0 / ("productRatePlanChargeId" -> "Is so bracing")
    }

    "Negate the given price" in {
      json must /("add") /# 0 / "chargeOverrides" /# 0 / ("price" -> -20)
    }

    "Set the correct specificEndDate date to the next bill date" in {
      json must /("add") /# 0 / "chargeOverrides" /# 0 / ("specificEndDate" -> "2016-10-30")
    }

    "Not make you a refund for -1 days if you're taking one day of holiday" in {
      val json =
        amendFromRefund(plans).writes(HolidayRefundCommand((20, PaymentHoliday(subName, 1 Sep 2016, 1 Sep 2016)), 31, now.plusYears(1), bs)).toString
      json must /("add") /# 0 / "chargeOverrides" /# 0 / ("HolidayEnd__c" -> "2016-09-01")
    }
  }

  "JSON reader" should {
    "work when the subscription has discount charges" in {
      Resource.getJson("rest/plans/Promo.json").as[Seq[HolidayRefund]] mustEqual List.empty
    }
  }

  "The SuspensionService class" should {

    val plans = HolidayRatePlanIds(prpId = ProductRatePlanId("prp"), prpcId = ProductRatePlanChargeId("prpc"))
    val config = ZuoraRestConfig("DEV", "https://example.com", "username", "password")
    val today = 1 Jan 2016

    type RequestSpy[A] = Writer[List[Request], A]

    val sundayBS = BillingSchedule(
      NonEmptyList(
        Bill(
          new LocalDate("2016-07-01"),
          1.month,
          NonEmptyList(
            BillItem("Sunday Paper", Some(SundayPaper), 5.0f, 5.0f),
          ),
        ),
      ),
    )

    val discountBS = BillingSchedule(
      NonEmptyList(
        Bill(
          1 Jul 2016,
          1.month,
          NonEmptyList(
            BillItem("Sunday Paper", Some(SundayPaper), 5.0f, 5.0f),
            BillItem("Discount", None, -5.0f, 100.0f),
          ),
        ),
        Bill(
          1 Aug 2016,
          1.month,
          NonEmptyList(
            BillItem("Sunday Paper", Some(SundayPaper), 5.0f, 5.0f),
            BillItem("Discount", None, -5.0f, 100.0f),
          ),
        ),
        Bill(
          1 Sep 2016,
          1.month,
          NonEmptyList(
            BillItem("Sunday Paper", Some(SundayPaper), 5.0f, 5.0f),
            BillItem("Discount", None, -5.0f, 100.0f),
          ),
        ),
      ),
    )

    // I think really this is a symptom of some unfortunate under-abstraction
    val spyRun: Request => RequestSpy[Response] = r =>
      Writer(
        List(r),
        r.method match {
          case "GET" => response(Resource.getJson("rest/Holiday.json").toString)
          case "PUT" => response(Json.obj("success" -> true).toString)
        },
      )

    val instance = new SuspensionService(plans, SimpleClient[RequestSpy](config, spyRun))

    "Return an error if you try to get a refund for days you don't get the paper on" in {
      instance.addHoliday(PaymentHoliday(subName, 21 Mar 2016, 22 Mar 2016), sundayBS, 31, today.plusYears(1), today).value mustEqual \/.left(
        NonEmptyList(NoRefundDue),
      ) // suspension attempted before first paper date
      instance.addHoliday(PaymentHoliday(subName, 20 Jun 2016, 21 Jun 2016), sundayBS, 31, today.plusYears(1), today).value mustEqual \/.left(
        NonEmptyList(NoRefundDue),
      ) // migrated sub which has no advance bill
      instance.addHoliday(PaymentHoliday(subName, 18 Jul 2016, 19 Jul 2016), sundayBS, 31, today.plusYears(1), today).value mustEqual \/.left(
        NonEmptyList(NoRefundDue),
      )
      instance.addHoliday(PaymentHoliday(subName, 20 Sep 2016, 21 Sep 2016), sundayBS, 31, today.plusYears(1), today).value mustEqual \/.left(
        NonEmptyList(NoRefundDue),
      ) // suspension attempted after last paper date
    }

    "Give you a refund if you do get the paper on the days, even if you're not paying" in {
      val start = 15 Jul 2016
      instance.addHoliday(PaymentHoliday(subName, start, start.plusDays(14)), discountBS, 31, today.plusYears(1), today).value mustEqual \/.right(
        PaymentHolidaySuccess(0f),
      )
    }

    "Return a PaymentHolidaySuccess if everything is okay" in {
      val start = 15 Jul 2016
      instance.addHoliday(PaymentHoliday(subName, start, start.plusDays(14)), sundayBS, 31, today.plusYears(1), today).value mustEqual \/.right(
        PaymentHolidaySuccess(2.31f),
      )
    }

    "Allow a suspension for migrated subs, now past their this-month's billing date, charging as per their next bill" in {
      val start = 10 Jun 2016
      instance.addHoliday(PaymentHoliday(subName, start, start.plusDays(14)), sundayBS, 31, today.plusYears(1), today).value mustEqual \/.right(
        PaymentHolidaySuccess(2.31f),
      )
    }

    "Send off an HTTP request to the right Zuora endpoint if we have a valid holiday to book" in {
      val ok = instance.addHoliday(PaymentHoliday(subName, 26 Jul 2016, 26 Oct 2016), sundayBS, 31, today.plusYears(1), today)
      ok.written.last.url.toString mustEqual "https://example.com/subscriptions/A-S1234"
    }

    "Not fire off any HTTP requests if validation of the request fails, we don't want to fulfill invalid holidays" in {
      val notEnoughNotice = instance.addHoliday(PaymentHoliday(subName, 2 Jan 2016, 3 Jan 2016), sundayBS, 31, today.plusYears(1), today)
      notEnoughNotice.value mustEqual \/.left(NonEmptyList(NotEnoughNotice))
      notEnoughNotice.written mustEqual List.empty
    }

    "Not let you book over holidays you already have" in {
      val duplicateStart = instance.addHoliday(PaymentHoliday(subName, 11 Aug 2016, 24 Aug 2016), sundayBS, 31, today.plusYears(1), today)
      val duplicateEnd = instance.addHoliday(PaymentHoliday(subName, 12 Aug 2016, 26 Aug 2016), sundayBS, 31, today.plusYears(1), today)
      val boundaryEnd = instance.addHoliday(PaymentHoliday(subName, 10 Aug 2016, 11 Aug 2016), sundayBS, 31, today.plusYears(1), today)

      duplicateStart.value mustEqual \/.left(NonEmptyList(AlreadyOnHoliday))
      duplicateEnd.value mustEqual \/.left(NonEmptyList(AlreadyOnHoliday))
      boundaryEnd.value mustEqual \/.left(NonEmptyList(AlreadyOnHoliday))
    }

    "When calling the get holiday function" should {

      "Ignore any deleted charges" in {
        val mock: Request => Id[Response] = _ => response(Resource.getJson("rest/Holiday-Deleted.json").toString)
        val instance = new SuspensionService(plans, SimpleClient[Id](config, mock))
        val name = Subscription.Name("subscriptionNumber")

        instance.getUnfinishedHolidays(name, 1 Jan 2016).toOption.get mustEqual Seq(
          (
            3.59f,
            PaymentHoliday(name, 28 Aug 2016, 28 Aug 2016),
          ), // Tests start == HolidayStart__c takes precidence over segment 2's effectiveStartDate
          (3.59f, PaymentHoliday(name, 4 Sep 2016, 4 Sep 2016)), // Tests start == effectiveStartDate
          (
            7.18f,
            PaymentHoliday(name, 11 Sep 2016, 18 Sep 2016),
          ), // Tests start == HolidayStart__c, effectiveStartDate is same as the RPC is segment 1
        )
      }

      "Ignore any historical holidays" in {
        val mock: Request => Id[Response] = _ => response(Resource.getJson("rest/Holiday-Deleted.json").toString)
        val instance = new SuspensionService(plans, SimpleClient[Id](config, mock))
        val name = Subscription.Name("subscriptionNumber")

        instance.getUnfinishedHolidays(name, 1 Jan 2017).toOption.get mustEqual Seq()
      }

    }
  }
}
