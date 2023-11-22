package com.gu.subscriptions.suspendresume

import com.gu.config.HolidayRatePlanIds
import com.gu.memsub.Subscription
import com.gu.subscriptions.suspendresume.SuspensionService.{
  HolidayRefund,
  HolidayRefundCommand,
  HolidayRenewCommand,
  PaymentHoliday,
  ZuoraReason,
  ZuoraResponse,
  ZuoraResult,
  ZuoraResults,
}
import org.joda.time.format.DateTimeFormat
import play.api.libs.json._
import org.joda.time.{Days, LocalDate}
import com.gu.memsub.subsv2.reads.CommonReads.localReads
import com.gu.memsub.subsv2.reads.CommonReads.localWrites
import play.api.libs.json.Json.JsValueWrapper

object JsonFormatters {

  implicit val zReadsReason = Json.reads[ZuoraReason]
  implicit val zReads = Json.reads[ZuoraResponse]
  implicit val zResultReads = Json.reads[ZuoraResult]
  implicit val zResultsReads = Json.reads[ZuoraResults]

  private case class HolidayRPC(
      price: Option[Float],
      effectiveStartDate: LocalDate,
      HolidayStart__c: Option[LocalDate],
      HolidayEnd__c: Option[LocalDate],
  )

  private case class Sub(ratePlans: Seq[RatePlan], subscriptionNumber: String)

  private case class RatePlan(ratePlanCharges: Seq[HolidayRPC], lastChangeType: Option[String])

  private implicit val hrpc = Json.reads[HolidayRPC]
  private implicit val rp = Json.reads[RatePlan]
  private implicit val s = Json.reads[Sub]

  implicit val r: Reads[Seq[HolidayRefund]] = new Reads[Seq[HolidayRefund]] {
    override def reads(json: JsValue): JsResult[Seq[HolidayRefund]] = s
      .reads(json)
      .map(s =>
        for {
          plan <- s.ratePlans.filterNot(_.lastChangeType.contains("Remove"))
          charge <- plan.ratePlanCharges
          price <- charge.price.toSeq
          holidayEnd <- charge.HolidayEnd__c.toSeq
        } yield {
          // Determine holiday start the new way using the custom field, falling back to the old way if not backfilled/backfillable
          val holidayStart = charge.HolidayStart__c.getOrElse(charge.effectiveStartDate)
          (-price, PaymentHoliday(Subscription.Name(s.subscriptionNumber), holidayStart, holidayEnd))
        },
      )
  }

  def holidayRenewal = new Writes[HolidayRenewCommand] {
    override def writes(command: HolidayRenewCommand): JsValue = {
      Json.obj(
        "requests" -> Seq(
          Json.obj(
            "Amendments" -> Seq(
              Json.obj(
                "ContractEffectiveDate" -> command.sub.termEndDate,
                "Description" -> "Early renewal of subscription to facilitate holiday",
                "EffectiveDate" -> command.sub.termEndDate,
                "Name" -> "Holiday early renew",
                "SubscriptionId" -> command.sub.id.get,
                "Type" -> "Renewal",
              ),
            ),
          ),
        ),
      )
    }
  }

  def amendFromRefund(plans: HolidayRatePlanIds): Writes[HolidayRefundCommand] = new Writes[HolidayRefundCommand] {
    override def writes(command: HolidayRefundCommand): JsValue = {
      Json.obj(
        "add" -> Seq(
          Json.obj(
            "productRatePlanId" -> plans.prpId.get,
            "contractEffectiveDate" -> command.firstDateOfHoliday,
            "serviceActivationDate" -> command.firstDateOfHoliday,
            "customerAcceptanceDate" -> command.firstDateOfHoliday, // Used by the fulfilment process (Deprecated)
            "chargeOverrides" -> Seq(
              Json.obj(
                "productRatePlanChargeId" -> plans.prpcId.get,
                "HolidayStart__c" -> command.firstDateOfHoliday, // To be used by the fulfilment process
                "HolidayEnd__c" -> command.lastDateOfHoliday, // Used by the fulfilment process
                "price" -> command.amountToRefund,
                "description" ->
                  s"""
First day of holiday: ${command.firstDateOfHoliday.toString(DateTimeFormat.forPattern("E d MMM yyyy"))}
Last day of holiday: ${command.lastDateOfHoliday.toString(DateTimeFormat.forPattern("E d MMM yyyy"))}
              """.trim,
                // This messes with the service period, hence why we have to use HolidayEnd__c for the fulfilment process
                "endDateCondition" -> "Specific_End_Date",
                "specificEndDate" -> command.dateToTriggerCharge,
              ),
            ),
          ),
        ),
      )
    }
  }
}
