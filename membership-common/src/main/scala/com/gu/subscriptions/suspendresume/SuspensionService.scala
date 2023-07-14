package com.gu.subscriptions.suspendresume

import java.lang.Math.min
import com.github.nscala_time.time.Imports.LocalDateOrdering
import com.gu.config.HolidayRatePlanIds
import com.gu.memsub.Benefit.PaperDay
import com.gu.memsub.subsv2.SubscriptionPlan
import com.gu.memsub.{BillingSchedule, Subscription}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.subscriptions.suspendresume.JsonFormatters._
import com.gu.subscriptions.suspendresume.RefundCalculator._
import com.gu.zuora.rest.SimpleClient
import org.joda.time.LocalDate.now
import org.joda.time.{Days, Interval, LocalDate}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scalaz.syntax.monad._
import scalaz.syntax.nel._
import scalaz.syntax.std.option._
import scalaz.{EitherT, Monad, NonEmptyList, Semigroup, Validation, ValidationNel, \/}

/** This service handles the wiring / HTTP side of suspending and resuming zuora subscriptions namely it validates incoming holiday requests and if
  * valid constructs JSON to send to Zuora or returns errors
  */
object SuspensionService {

  implicit class BetterLocalDate(in: LocalDate) {
    def withDayOfMonthOrLastDay(dayToSet: Int): LocalDate = {
      val lastDayOfMonth = in.dayOfMonth.withMaximumValue.getDayOfMonth
      in.withDayOfMonth(min(dayToSet, lastDayOfMonth))
    }
  }

  case class PaymentHoliday(subscription: Subscription.Name, start: LocalDate, finish: LocalDate)

  case class PaymentHolidaySuccess(refund: Float)

  type HolidayRefund = (Float, PaymentHoliday)

  case class HolidayRefundCommand(holidayRefund: HolidayRefund, billCycleDay: Int, termEndDate: LocalDate, bs: BillingSchedule) {
    val amountToRefund: Float = -1 * holidayRefund._1
    val firstDateOfHoliday: LocalDate = holidayRefund._2.start
    val lastDateOfHoliday: LocalDate = holidayRefund._2.finish
    val nextInvoice: Option[LocalDate] = bs.invoices.list.toList.filter(_.totalGross > 0).map(_.date).sorted.find(_.isAfter(firstDateOfHoliday))
    // Fallback for when there are no matching invoices
    val nextMonthsInvoiceDate: LocalDate = {
      if (firstDateOfHoliday.dayOfMonth.get < billCycleDay) {
        firstDateOfHoliday.withDayOfMonthOrLastDay(billCycleDay)
      } else {
        firstDateOfHoliday.plusMonths(1).withDayOfMonthOrLastDay(billCycleDay)
      }
    }

    val dateToTriggerCharge: LocalDate = nextInvoice getOrElse Seq(termEndDate, nextMonthsInvoiceDate).min
  }

  case class HolidayRenewCommand(sub: com.gu.memsub.subsv2.Subscription[SubscriptionPlan.Delivery])

  case class ZuoraResponse(
      success: Boolean,
      processId: Option[String],
      reasons: Option[List[ZuoraReason]],
  )

  case class ZuoraReason(code: Long, message: String)

  case class ZuoraResults(results: Seq[ZuoraResult])
  case class ZuoraResult(SubscriptionId: String, TotalDeltaTcv: Double, AmendmentIds: Seq[String], TotalDeltaMrr: Double, Success: Boolean)

  sealed trait RefundError {
    val code: String
  }

  case object NoRefundDue extends RefundError {
    val code = "NoRefundDue"
  }

  case object NotEnoughNotice extends RefundError {
    val code = "NotEnoughNotice"
  }

  case object AlreadyOnHoliday extends RefundError {
    val code = "AlreadyOnHoliday"
  }

  case object NegativeDays extends RefundError {
    val code = "NegativeDays"
  }

  case class BadZuoraJson(got: String) extends RefundError {
    val code = "BadZuoraJson"
  }

  type ErrNel = NonEmptyList[RefundError]

  implicit object UnitSemigroup extends Semigroup[Unit] {
    override def append(f1: Unit, f2: => Unit): Unit = ()
  }

  private def checkIntersections(holiday: PaymentHoliday, current: Seq[HolidayRefund]) = {
    val currentIntervals =
      current.map(r => Try(new Interval(r._2.start.toDateTimeAtStartOfDay, r._2.finish.plusDays(1).toDateTimeAtStartOfDay.minusMillis(1))))
    val prospectiveInterval = new Interval(holiday.start.toDateTimeAtStartOfDay, holiday.finish.plusDays(1).toDateTimeAtStartOfDay.minusMillis(1))
    currentIntervals.forall(cur =>
      cur.map(_.overlaps(prospectiveInterval)) match {
        case Success(true) => false // if overlaps, then is not ok
        case Success(false) => true // if doesn't overlap, then it's ok
        case Failure(ex) =>
          SafeLogger.error(scrub"broken holiday ended before it started! Sub: ${holiday.subscription.get}", ex)
          true // if we couldn't read the holiday period, it's ok, but log an error for someone to sort it out
      },
    )
  }

  def validateHoliday(holiday: PaymentHoliday, today: LocalDate = now()): ValidationNel[RefundError, Unit] = {
    if (holiday.finish.isBefore(holiday.start)) {
      Validation.failureNel(NegativeDays)
    } else if (holiday.start.isBefore(today.plusDays(3))) {
      Validation.failureNel(NotEnoughNotice)
    } else {
      Validation.s[NonEmptyList[RefundError]](())
    }
  }

  def holidayToDays(start: LocalDate, finish: LocalDate): Seq[LocalDate] = {
    val realStart = if (start.isAfter(finish)) finish else start
    val realFinish = if (finish.isBefore(start)) start else finish
    Seq(realStart) ++ (1 to Days.daysBetween(realStart, realFinish).getDays).map(realStart.plusDays)
  }

  def holidayToSuspendedDays(holidayRefunds: Seq[HolidayRefund], products: Seq[PaperDay]): Int =
    holidayRefunds.flatMap(x => SuspensionService.holidayToDays(x._2.start, x._2.finish)).map(dayToProduct).count(products.contains)

}

class SuspensionService[M[_]: Monad](plans: HolidayRatePlanIds, simpleRest: SimpleClient[M]) {

  import SuspensionService._

  private implicit val writer = amendFromRefund(plans)
  private implicit val renewalWriter = holidayRenewal

  def toNel(in: RefundError): ErrNel = in.wrapNel

  type ET[A] = EitherT[ErrNel, M, A]

  def getUnfinishedHolidays(in: Subscription.Name, today: LocalDate): M[\/[String, Seq[(Float, PaymentHoliday)]]] = {
    {
      for {
        allHolidays <- EitherT(simpleRest.get[Seq[HolidayRefund]](s"subscriptions/${in.get}"))
      } yield allHolidays.filterNot(_._2.finish.isBefore(today)).sortBy(_._2.start)
    }.run

  }

  def subThatNeedsRenewing(sub: com.gu.memsub.subsv2.Subscription[SubscriptionPlan.Delivery], holiday: PaymentHoliday) = {
    if (holiday.finish.isAfter(sub.termEndDate)) Some(sub) else None
  }

  def renewIfNeeded(sub: com.gu.memsub.subsv2.Subscription[SubscriptionPlan.Delivery], holiday: PaymentHoliday): M[\/[String, Unit]] = {
    subThatNeedsRenewing(sub, holiday)
      .map { sub =>
        val response = simpleRest.post[HolidayRenewCommand, ZuoraResults]("action/amend", HolidayRenewCommand(sub))
        response.map(_.flatMap { response =>
          if (response.results.forall(_.Success)) \/.r[String](())
          else {
            SafeLogger.warn("we tried to renew, but it didn't work")
            \/.l[Unit]("Renewal call failed.")
          }
        })
      }
      .getOrElse(\/.right[String, Unit](()).point[M])
  }

  def addHoliday(
      in: PaymentHoliday,
      bs: BillingSchedule,
      billCycleDay: Int,
      termEndDate: LocalDate,
      today: LocalDate = now,
  ): M[ErrNel \/ PaymentHolidaySuccess] = {
    def err(reason: RefundError) = EitherT.left[ErrNel, M, PaymentHolidaySuccess](toNel(reason))

    (for {
      _ <- EitherT(validateHoliday(in, today).disjunction.point[M])
      currentHolidays <- EitherT(getUnfinishedHolidays(in.subscription, today)).leftMap(e => toNel(BadZuoraJson(e)))
      _ <- Monad[ET].unlessM(checkIntersections(in, currentHolidays))(err(AlreadyOnHoliday))
      refund <- EitherT((calculateRefund(holidayToDays(in.start, in.finish), bs) \/> toNel(NoRefundDue)).point[M])
      response <- EitherT(
        simpleRest.put[HolidayRefundCommand, ZuoraResponse](
          s"subscriptions/${in.subscription.get}",
          HolidayRefundCommand((refund, in), billCycleDay, termEndDate, bs),
        ),
      ).leftMap(e => toNel(BadZuoraJson(e)))
      _ <- Monad[ET].unlessM(response.success)(err(BadZuoraJson(s"response indicated no success: $response")))
    } yield PaymentHolidaySuccess(refund)).run
  }
}
