package services.subscription

import com.gu.memsub
import com.gu.memsub.Subscription.{AccountId, ProductRatePlanId, RatePlanId}
import com.gu.memsub.subsv2.SubscriptionPlan._
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.salesforce.ContactId
import org.joda.time.{LocalDate, LocalTime}
import play.api.libs.json._
import scalaz._

import scala.concurrent.Future

case class SubIds(ratePlanId: RatePlanId, productRatePlanId: ProductRatePlanId)

object SubscriptionService {
  type SoapClient = ContactId => Future[List[memsub.Subscription.AccountId]]
  type CatalogMap = Map[ProductRatePlanId, CatalogZuoraPlan]
}

trait SubscriptionService {
  def get[P <: AnyPlan: SubPlanReads](
      name: memsub.Subscription.Name,
      isActiveToday: Boolean = false,
  ): Future[Option[Subscription[P]]]

  def current[P <: AnyPlan: SubPlanReads](contact: ContactId)(implicit logPrefix: LogPrefix): Future[List[Subscription[P]]]

  def since[P <: AnyPlan: SubPlanReads](onOrAfter: LocalDate)(contact: ContactId)(implicit logPrefix: LogPrefix): Future[List[Subscription[P]]]

  def recentlyCancelled(
      contact: ContactId,
      today: LocalDate = LocalDate.now(),
      lastNMonths: Int = 3, // cancelled in the last N months
  )(implicit ev: SubPlanReads[AnyPlan], logPrefix: LogPrefix): Future[String \/ List[Subscription[AnyPlan]]]

  def subscriptionsForAccountId[P <: AnyPlan: SubPlanReads](accountId: AccountId): Future[Disjunction[String, List[Subscription[P]]]]

  def jsonSubscriptionsFromContact(contact: ContactId)(implicit logPrefix: LogPrefix): Future[Disjunction[String, List[JsValue]]]

  def jsonSubscriptionsFromAccount(accountId: AccountId): Future[Disjunction[String, List[JsValue]]]

  /** find the best current subscription for the salesforce contact TODO get rid of this and use pattern matching instead
    */
  def either[FALLBACK <: AnyPlan, PREFERRED <: AnyPlan](
      contact: ContactId,
  )(implicit
      a: SubPlanReads[FALLBACK],
      b: SubPlanReads[PREFERRED],
      logPrefix: LogPrefix,
  ): Future[\/[String, Option[Subscription[FALLBACK] \/ Subscription[PREFERRED]]]]

  def getSubscription(contact: ContactId)(implicit a: SubPlanReads[Contributor], logPrefix: LogPrefix): Future[Option[Subscription[Contributor]]]

  /** find the current subscription for the given subscription number TODO get rid of this and use pattern matching instead
    */
  def either[FALLBACK <: AnyPlan, PREFERRED <: AnyPlan](
      name: memsub.Subscription.Name,
  )(implicit a: SubPlanReads[FALLBACK], b: SubPlanReads[PREFERRED]): Future[\/[String, Subscription[FALLBACK] \/ Subscription[PREFERRED]]]

  // this is a back door to find the subscription discount ids so we can delete when people upgrade
  // just need the id and prp id
  def backdoorRatePlanIds(name: com.gu.memsub.Subscription.Name): Future[String \/ List[SubIds]]

  /** fetched with /v1/subscription/{key}?charge-detail=current-segment which zeroes out all the non-active charges
    *
    * There are multiple scenarios
    *   - period between acquisition date and fulfilment date => None which indicates cancel now
    *     - usually contractEffectiveDate to customerAcceptanceDate, except in the case of Guardian Weekly+6for6 where customerAcceptanceDate
    *       indicates start of GW proper invoiced period instead of start of 6for6 invoiced period despite GW+6for6 being just a regular Subscription
    *       with multiple products.
    *     - free trial, or user choose start date of first issue in the future (lead time)
    *   - Subscription within invoiced period proper => Some(endOfLastInvoicePeriod)
    *   - free product => None which indicates cancel now
    *   - edge case of being on the first day of invoice period however bill run has not yet happened => ERROR
    *   - Today is after end of last invoice date and bill run has already completed => ERROR
    *
    * @return
    *   Right(None) indicates cancel now, Right(Some("yyyy-mm-dd")) indicates cancel at end of last invoiced period Left indicates error and MMA
    *   should not proceed with automatic cancelation
    */
  def decideCancellationEffectiveDate[P <: SubscriptionPlan.AnyPlan: SubPlanReads](
      subscriptionName: memsub.Subscription.Name,
      wallClockTimeNow: LocalTime = LocalTime.now(),
      today: LocalDate = LocalDate.now(),
  ): EitherT[String, Future, Option[LocalDate]]
}
