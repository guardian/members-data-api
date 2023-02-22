package services.subscription

import com.gu.memsub
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.{AnyPlan, Contributor}
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.salesforce.ContactId
import com.gu.zuora.soap.models.Queries.Account
import monitoring.CreateMetrics
import org.joda.time.{LocalDate, LocalTime}
import play.api.libs.json.JsValue
import scalaz.{Disjunction, DisjunctionT, \/}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

class SubscriptionServiceWithMetrics(wrapped: SubscriptionService, createMetrics: CreateMetrics)(implicit val ec: ExecutionContext)
    extends SubscriptionService {
  private val metrics = createMetrics.forService(wrapped.getClass)

  override def get[P <: AnyPlan: SubPlanReads](name: memsub.Subscription.Name, isActiveToday: Boolean): Future[Option[Subscription[P]]] =
    metrics.measureDuration("get")(wrapped.get(name, isActiveToday))

  override def current[P <: AnyPlan: SubPlanReads](contact: ContactId): Future[List[(Account, Subscription[P])]] =
    metrics.measureDuration("current")(wrapped.current(contact))

  override def since[P <: AnyPlan: SubPlanReads](onOrAfter: LocalDate)(contact: ContactId): Future[List[(Account, Subscription[P])]] =
    metrics.measureDuration("since")(wrapped.since(onOrAfter)(contact))

  override def recentlyCancelled(contact: ContactId, today: LocalDate, lastNMonths: Int)(implicit
      ev: SubPlanReads[AnyPlan],
  ): Future[String \/ List[(Account, Subscription[AnyPlan])]] =
    metrics.measureDuration("recentlyCancelled")(wrapped.recentlyCancelled(contact, today, lastNMonths))

  override def subscriptionsForAccountId[P <: AnyPlan: SubPlanReads](accountId: AccountId): Future[Disjunction[String, List[Subscription[P]]]] =
    metrics.measureDuration("subscriptionsForAccountId")(wrapped.subscriptionsForAccountId(accountId))

  override def jsonSubscriptionsFromContact(contact: ContactId): Future[Disjunction[String, List[(Account, JsValue)]]] =
    metrics.measureDuration("jsonSubscriptionsFromContact")(wrapped.jsonSubscriptionsFromContact(contact))

  override def jsonSubscriptionsFromAccount(accountId: AccountId): Future[Disjunction[String, List[JsValue]]] =
    metrics.measureDuration("jsonSubscriptionsFromAccount")(wrapped.jsonSubscriptionsFromAccount(accountId))

  /** find the best current subscription for the salesforce contact TODO get rid of this and use pattern matching instead
    */
  override def either[FALLBACK <: AnyPlan, PREFERRED <: AnyPlan](
      contact: ContactId,
  )(implicit
      a: SubPlanReads[FALLBACK],
      b: SubPlanReads[PREFERRED],
  ): Future[String \/ Option[(Account, Subscription[FALLBACK] \/ Subscription[PREFERRED])]] =
    metrics.measureDuration("eitherByContact")(wrapped.either(contact))

  override def getSubscription(contact: ContactId)(implicit a: SubPlanReads[Contributor]): Future[Option[(Account, Subscription[Contributor])]] =
    metrics.measureDuration("getSubscription")(wrapped.getSubscription(contact))

  /** find the current subscription for the given subscription number TODO get rid of this and use pattern matching instead
    */
  override def either[FALLBACK <: AnyPlan, PREFERRED <: AnyPlan](
      name: memsub.Subscription.Name,
  )(implicit a: SubPlanReads[FALLBACK], b: SubPlanReads[PREFERRED]): Future[String \/ (Subscription[FALLBACK] \/ Subscription[PREFERRED])] =
    metrics.measureDuration("eitherByName")(wrapped.either(name))

  override def backdoorRatePlanIds(name: memsub.Subscription.Name): Future[String \/ List[SubIds]] =
    metrics.measureDuration("backdoorRatePlanIds")(wrapped.backdoorRatePlanIds(name))

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
  override def decideCancellationEffectiveDate[P <: AnyPlan: SubPlanReads](
      subscriptionName: memsub.Subscription.Name,
      wallClockTimeNow: LocalTime,
      today: LocalDate,
  ): DisjunctionT[String, Future, Option[LocalDate]] =
    wrapped.decideCancellationEffectiveDate(subscriptionName, wallClockTimeNow, today)
}
