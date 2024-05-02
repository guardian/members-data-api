package services.subscription

import _root_.services.zuora.rest.SimpleClient
import _root_.services.zuora.soap.ZuoraSoapService
import com.gu.memsub
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan._
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads.ProductIds
import com.gu.memsub.subsv2.reads.SubJsonReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.memsub.subsv2.services.SubscriptionTransform.getRecentlyCancelledSubscriptions
import com.gu.memsub.subsv2.services.Trace.Traceable
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import org.joda.time.{LocalDate, LocalTime}
import play.api.libs.json.{Reads => JsReads, _}
import scalaz._
import scalaz.syntax.all._
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ZuoraSubscriptionService(pids: ProductIds, futureCatalog: => Future[CatalogMap], rest: SimpleClient, soap: ZuoraSoapService)(implicit
    t: Monad[Future],
    ec: ExecutionContext,
) extends SubscriptionService
    with SafeLogging {
  private implicit val idReads = new JsReads[JsValue] {
    override def reads(json: JsValue): JsResult[JsValue] = JsSuccess(json)
  }

  /** Time by which Bill Run should have run and completed. Usually starts around 5AM and takes 1 hour.
    */
  private final val BillRunCompletedByTime = LocalTime.parse("12:00")

  /** Fetch a subscription by its subscription name
    *
    * @param isActiveToday
    *   If true return subscription with currently (today) active charge (effectiveStartDate <= today’s date < effectiveEndDate). Other rate plan
    *   charges are effectively zeroed out, that is, returned as empty list.
    *
    * By default it is set to false which is supposed to, as per docs, return the last rate plan charge on the subscription. The last rate plan charge
    * is the last one in the order of time on the subscription rather than the most recent changed charge on the subscription. However in practice
    * this is not always the case. It also returns all the historical charges that have been removed that is charges with '"lastChangeType":
    * "Remove"'.
    *
    * FIXME: We should change to true as default as we are usually interested in only what the user has right now. We could simplify much filtering
    * logic then, and possibly it would resolve automatically few bugs. (All clients would have to be tested!)
    *
    * @see
    *   Query Parameters section of https://www.zuora.com/developer/api-reference/#operation/GET_SubscriptionsByKey
    * @see
    *   https://community.zuora.com/t5/Admin-Settings-Ideas/Get-current-active-subscription-rate-plans/idi-p/19049
    */
  def get[P <: AnyPlan: SubPlanReads](
      name: memsub.Subscription.Name,
      isActiveToday: Boolean = false,
  )(implicit logPrefix: LogPrefix): Future[Option[Subscription[P]]] = {

    val url =
      if (isActiveToday)
        s"subscriptions/${name.get}?charge-detail=current-segment" // (effectiveStartDate <= today’s date < effectiveEndDate).
      else
        s"subscriptions/${name.get}" // FIXME: equivalent to ?charge-detail=last-segment which returns even removed historical charges. We should not have this as default.

    val futureSubJson = rest.get[JsValue](url)(idReads, logPrefix)

    futureSubJson.flatMap { subJson =>
      futureCatalog.map { catalog =>
        // FIXME: Why naming indicates multiple subscriptions? There should be only one sub per provided name.
        val allSubscriptionsForSubscriberName = subJson.flatMap { jsValue =>
          SubscriptionTransform.getSubscription[P](catalog, pids)(jsValue).withTrace("getAllValidSubscriptionsFromJson")
        }
        warnOnMissingChargedThroughDate(allSubscriptionsForSubscriberName)
        allSubscriptionsForSubscriberName.leftMap(error => logger.warn(s"Error from sub service for $name: $error")).toOption

      }
    }
  }

  /** A paid subscription that is active today should have been invoiced, which means Bill Run should have happened, and so chargedThroughDate should
    * be populated. If a paid subscription is missing chargeThroughDate also known as End of Last Invoice Period, or day after service period end
    * date, then the likelihood of bugs increases. For example, holiday credit or cancellation effective date should be applied on End of Last Invoice
    * Period date, and we have had bugs around both of these functionalities.
    *
    * Free trials are an exception, however we might be able to execute real-time Bill Run at point of acquisition with future target date in which
    * case chargedThroughDate date should again be populated even though payment run has not happened yet. Currently there is assumption in the
    * codebase if chargedThroughDate does not exist on paid product then it is free trial, however this is not a safe assumption. We should explicitly
    * be determining free free trial state by perhaps if(sub.startDate <= today && sub.acceptanceDate > today) then free trial.
    *
    * This logging side-effect should make more visible in which scenarios we are missing chargedThroughDate.
    */
  private def warnOnMissingChargedThroughDate[P <: AnyPlan: SubPlanReads](
      subscription: String \/ Subscription[P],
  )(implicit logPrefix: LogPrefix): Unit =
    Try { // just to make sure it is not interfering with main business logic
      subscription.foreach { sub =>
        sub.plan match {
          case p: PaidSubscriptionPlan[_, _] if p.chargedThrough.isEmpty =>
            logger.warn(s"chargedThroughDate (end of last invoice date) does not exist for ${sub.name}")
          case _ => // do nothing
        }
      }
    }

  /** Using fromContact above fetch all the subscriptions for a given contact
    */
  private def subscriptionsForContact[P <: AnyPlan: SubPlanReads](
      transform: SubscriptionTransform.TimeRelativeSubTransformer[P],
  )(contact: ContactId)(implicit logPrefix: LogPrefix): Future[List[Subscription[P]]] = {
    val subJsonsFuture = jsonSubscriptionsFromContact(contact)

    subJsonsFuture.flatMap { subJsonsEither =>
      futureCatalog.map { catalog =>
        val highLevelSubscriptions = subJsonsEither.map { subJsons =>
          transform(catalog, pids)(subJsons)
            .leftMap(e => logger.warn(s"Error from sub service for contact $contact: $e"))
            .toList
            .flatMap(_.list.toList) // returns an empty list if there's an error
        }
        highLevelSubscriptions
          .leftMap(e => logger.warn(s"Error from sub service for contact $contact: $e"))
          .toList
          .flatten // returns an empty list if there's an error
      }
    }
  }

  def current[P <: AnyPlan: SubPlanReads](contact: ContactId)(implicit logPrefix: LogPrefix): Future[List[Subscription[P]]] =
    subscriptionsForContact(SubscriptionTransform.getCurrentSubscriptions[P])(contact)

  def since[P <: AnyPlan: SubPlanReads](onOrAfter: LocalDate)(contact: ContactId)(implicit logPrefix: LogPrefix): Future[List[Subscription[P]]] =
    subscriptionsForContact(SubscriptionTransform.getSubscriptionsActiveOnOrAfter[P](onOrAfter))(contact)

  def recentlyCancelled(
      contact: ContactId,
      today: LocalDate = LocalDate.now(),
      lastNMonths: Int = 3, // cancelled in the last N months
  )(implicit ev: SubPlanReads[AnyPlan], logPrefix: LogPrefix): Future[String \/ List[Subscription[AnyPlan]]] = {
    (for {
      catalog <- EitherT(futureCatalog.map(\/.right[String, CatalogMap]))
      jsonSubs <- EitherT(jsonSubscriptionsFromContact(contact))
      subs <- EitherT(Monad[Future].pure(getRecentlyCancelledSubscriptions[AnyPlan](today, lastNMonths, catalog, pids, jsonSubs)))
    } yield subs).run
  }

  def subscriptionsForAccountId[P <: AnyPlan: SubPlanReads](
      accountId: AccountId,
  )(implicit logPrefix: LogPrefix): Future[Disjunction[String, List[Subscription[P]]]] = {
    val subsAsJson = jsonSubscriptionsFromAccount(accountId)

    subsAsJson.flatMap { subJsonsEither =>
      futureCatalog.map { catalog =>
        subJsonsEither.rightMap { subJsons =>
          SubscriptionTransform.getCurrentSubscriptions[P](catalog, pids)(subJsons).toList.flatMap(_.list.toList)
        }
      }
    }
  }

  def jsonSubscriptionsFromContact(contact: ContactId)(implicit logPrefix: LogPrefix): Future[Disjunction[String, List[JsValue]]] = {
    (for {
      account <- ListT[SimpleEitherT, AccountId](
        EitherT[String, Future, IList[AccountId]](soap.getAccountIds(contact).map(l => \/.r[String](IList.fromSeq(l)))),
      )
      subJson <- ListT[SimpleEitherT, JsValue](EitherT(jsonSubscriptionsFromAccount(account)).map(IList.fromSeq))
    } yield subJson).toList.run
  }

  def jsonSubscriptionsFromAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[Disjunction[String, List[JsValue]]] =
    rest.get[List[JsValue]](s"subscriptions/accounts/${accountId.get}")(multiSubJsonReads, implicitly)

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
  )(implicit logPrefix: LogPrefix): SimpleEitherT[Option[LocalDate]] = {
    EitherT(
      OptionT(get[P](subscriptionName, isActiveToday = true)).fold(
        zuoraSubscriptionWithCurrentSegment => {
          val paidPlans =
            zuoraSubscriptionWithCurrentSegment.plans.list.collect { case paidPlan: PaidSubscriptionPlan[_, _] => paidPlan }
          val billRunHasAlreadyHappened = wallClockTimeNow.isAfter(BillRunCompletedByTime)

          paidPlans match {
            case paidPlan1 :: paidPlan2 :: _ => \/.l[Option[LocalDate]]("Failed to determine specific single active paid rate plan charge")

            case paidPlan :: Nil => // single rate plan charge identified
              paidPlan.chargedThrough match {
                case Some(endOfLastInvoicePeriod) =>
                  val endOfLastInvoiceDateIsBeforeOrOnToday = endOfLastInvoicePeriod.isBefore(today) || endOfLastInvoicePeriod.isEqual(today)
                  if (endOfLastInvoiceDateIsBeforeOrOnToday && billRunHasAlreadyHappened)
                    \/.left(
                      "chargedThroughDate exists but seems out-of-date because bill run should have moved chargedThroughDate to next invoice period. Investigate ASAP!",
                    )
                  else
                    \/.r[String](Some(endOfLastInvoicePeriod))
                case None =>
                  if (paidPlan.start.equals(today) && !billRunHasAlreadyHappened) // effectiveStartDate exists but not chargedThroughDate
                    \/.l[Option[LocalDate]](s"Invoiced period has started today, however Bill Run has not yet completed (it usually runs around 6am)")
                  else
                    \/.l[Option[LocalDate]](s"Unknown reason for missing chargedThroughDate. Investigate ASAP!")
              }

            case Nil => \/.r[String](Option.empty[LocalDate]) // free product so cancel now
          }
        },
        none = \/.right(Option.empty[LocalDate]),
      ), // we are within period between acquisition date and fulfilment date so cancel now (lead time / free trial)
    )
  }

}
