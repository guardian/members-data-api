package subscriptions.suspendresume

import com.github.nscala_time.time.Imports._
import models.subscription.Benefit._
import models.subscription._
import org.joda.time.DateTimeConstants._
import org.joda.time.Days.daysBetween
import org.joda.time.LocalDate
import scalaz.std.option._
import scalaz.{Monad, NonEmptyList}

import scala.math.BigDecimal.RoundingMode.HALF_UP
import scala.math.BigDecimal.decimal

object RefundCalculator {
  private val maximumDaysInAYear = 366
  private val maximumDaysInAQuarter = 31 + 31 + 30 // e.g. July + August + September
  private val maximumDaysInAMonth = 31

  private implicit class SameMonthAndYear(in: BillingSchedule.Bill) {
    def covers(day: LocalDate): Boolean = {
      in.date.month == day.month && in.date.year == day.year
    }
  }

  private implicit class ForPaperDay(invoices: NonEmptyList[BillingSchedule.Bill]) {
    def onlyContainingChargesForPaperDay(paperDay: PaperDay): List[BillingSchedule.Bill] = {
      invoices.list.toList.filter(_.items.list.toList.exists(item => item.product.contains(paperDay) && item.amount > 0))
    }
  }

  private implicit class WithoutAdjustments(charges: NonEmptyList[BillingSchedule.BillItem]) {
    def withoutAdjustments: List[BillingSchedule.BillItem] = {
      // We check the name because there's a special Echo-Legacy 100% Discount Adjustment which is excluded by the CatalogService.
      // This will be removed once the catalog has Discount charge types in there.
      charges.list.toList.filterNot(bi => bi.product.contains(Adjustment) || bi.name == "Adjustment charge")
    }
  }

  /** There are customers who have invoice adjustments on their subscriptions. These adjustments cover things like compensation discounts, pro-rated
    * late payments, no-charge extended terms, etc. None of these product rate plan charges should affect the notional "value" of the subscription,
    * and therefore should not affect any refund the customer gets for a suspended paper, or when somehow cancelling their subscription.
    */
  def dayToPrice(bs: BillingSchedule)(day: LocalDate): Option[Float] = {
    val paperDay = dayToProduct(day)
    val relevantInvoices = bs.invoices.onlyContainingChargesForPaperDay(paperDay).sortBy(_.date)

    // Drive the price from the covering month's invoice, if one is not found, take next one
    val invoiceWhichDeterminesPrice = relevantInvoices.find(_.covers(day)) orElse relevantInvoices.find(_.date.isAfter(day))

    for {
      invoice <- invoiceWhichDeterminesPrice
      item <- invoice.items.list.find(_.product.contains(paperDay)).toOption
      itemNonZero <- Monad[Option].unlessM(item.unitPrice > 0f)(None)
      gross = invoice.items.withoutAdjustments.filter(_.amount >= 0).map(_.amount).sum
      deductions = -invoice.items.withoutAdjustments.filter(_.amount <= 0).map(_.amount).sum
      deductionsPercent = deductions / gross
    } yield item.unitPrice * (1 - deductionsPercent)
  }

  // None causes an error to be thrown in the front end which advises customer to phone the call centre.
  def calculateRefund(days: Seq[LocalDate], bs: BillingSchedule): Option[Float] = {
    val refundGuidePriceForEachDay = days.flatMap(dayToPrice(bs)(_).toList)
    val toalRefundAtGuidePrice = refundGuidePriceForEachDay.reduceOption(_ + _)
    toalRefundAtGuidePrice.flatMap(scaleDownFromBillingPeriodToWeeklyPrice(bs))
  }

  def scaleDownFromBillingPeriodToWeeklyPrice(bs: BillingSchedule)(price: Float): Option[Float] = {
    val likelyBillingPeriod = avgPeriodBetweenInvoices(bs)
    if (likelyBillingPeriod <= maximumDaysInAMonth) {
      Some(monthlyPriceToWeekly(price))
    } else if (likelyBillingPeriod <= maximumDaysInAQuarter) {
      Some(quarterlyPriceToWeekly(price))
    } else if (likelyBillingPeriod <= maximumDaysInAYear) {
      Some(annualPriceToWeekly(price))
    } else {
      None // Customer has some unsupported billing period, or their invoice schedule is messed up.
    }
  }

  def avgPeriodBetweenInvoices(bs: BillingSchedule): Int = {
    val dates = bs.invoices.list.filter(_.totalGross > 0).map(_.date)
    (dates.headOption, dates.lastOption) match {
      case (Some(first), Some(last)) => daysBetween(first, last).dividedBy(dates.length).getDays
      case _ => 0
    }
  }

  def annualPriceToWeekly(price: Float): Float = (decimal(price) / 52).setScale(2, HALF_UP).floatValue

  def quarterlyPriceToWeekly(price: Float): Float = (decimal(price) * 4 / 52).setScale(2, HALF_UP).floatValue

  def monthlyPriceToWeekly(price: Float): Float = (decimal(price) * 12 / 52).setScale(2, HALF_UP).floatValue

  def dayToProduct(in: LocalDate): PaperDay = in.getDayOfWeek match {
    case MONDAY => MondayPaper
    case TUESDAY => TuesdayPaper
    case WEDNESDAY => WednesdayPaper
    case THURSDAY => ThursdayPaper
    case FRIDAY => FridayPaper
    case SATURDAY => SaturdayPaper
    case SUNDAY => SundayPaper
  }
}
