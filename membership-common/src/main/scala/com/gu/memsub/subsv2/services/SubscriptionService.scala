package com.gu.memsub.subsv2.services

import com.gu.memsub
import com.gu.memsub.Subscription.{AccountId, ProductRatePlanId, RatePlanId}
import com.gu.memsub.promo.LogImplicit.Loggable
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads.ProductIds
import com.gu.memsub.subsv2.reads.SubJsonReads._
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import com.gu.zuora.SoapClient
import com.gu.zuora.rest.SimpleClient
import org.joda.time.{LocalDate, LocalTime}
import play.api.libs.json.{Reads => JsReads, _}
import scalaz._
import scalaz.syntax.all._

case class SubIds(ratePlanId: RatePlanId, productRatePlanId: ProductRatePlanId)

object SubscriptionService {
  type CatalogMap = Map[ProductRatePlanId, CatalogZuoraPlan]
}

/*
Sequence turns a list of either into an either of list.  In this case, it does it by putting all the rights into a list and returning
that as a right.  However if there are no rights, it will return a left of any lefts.
This is mostly useful if we want to try a load of things and hopefully one will succeed.  It's not too good in case things
go wrong, we don't know which ones should have failed and which shouldn't have.  But at least it keeps most of the errors.
 */
object Sequence {

  def apply[A](eitherList: List[String \/ A]): String \/ NonEmptyList[A] = {
    val zero = (List[String](), List[A]())
    val product = eitherList.foldRight(zero)({
      case (-\/(left), (accuLeft, accuRight)) => (left :: accuLeft, accuRight)
      case (\/-(right), (accuLeft, accuRight)) => (accuLeft, right :: accuRight)
    })
    // if any are right, return them all, otherwise return all the left
    product match {
      case (Nil, Nil) => -\/("no subscriptions found at all, even invalid ones") // no failures or successes
      case (errors, Nil) => -\/(errors.mkString("\n")) // no successes
      case (_, result :: results) => \/-(NonEmptyList.fromSeq(result, results)) // discard some errors as long as some worked (log it?)
    }
  }

}

// since we don't have a stack to trace, we need to make our own
object Trace {

  implicit class Traceable[T](t: String \/ T) {
    def withTrace(message: String): String \/ T = t match {
      case -\/(e) => -\/(s"$message: {$e}")
      case right => right
    }
  }

}

class SubscriptionService[M[_]: Monad](pids: ProductIds, futureCatalog: => M[CatalogMap], rest: SimpleClient[M], soap: SoapClient[M])
    extends SafeLogging {
  type EitherTM[A] = EitherT[String, M, A]

  private val idReads = new JsReads[JsValue] {
    override def reads(json: JsValue): JsResult[JsValue] = JsSuccess(json)
  }

  /** Time by which Bill Run should have run and completed. Usually starts around 5AM and takes 1 hour.
    */
  final val BillRunCompletedByTime = LocalTime.parse("12:00")

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
  def get(name: memsub.Subscription.Name, isActiveToday: Boolean = false)(implicit logPrefix: LogPrefix): M[Option[Subscription]] = {

    val url =
      if (isActiveToday)
        s"subscriptions/${name.get}?charge-detail=current-segment" // (effectiveStartDate <= today’s date < effectiveEndDate).
      else
        s"subscriptions/${name.get}" // FIXME: equivalent to ?charge-detail=last-segment which returns even removed historical charges. We should not have this as default.

    for {
      subJson <- rest.get[JsValue](url)(idReads, logPrefix)
      catalog <- futureCatalog
    } yield for {
      jsValue <- subJson.withLogging(s"get subscription $name from zuora").toOption
      subscription <- SubscriptionTransform
        .getSubscription(catalog, pids)(jsValue)
        .withLogging(s"getAllValidSubscriptionsFromJson for $name")
        .toOption
    } yield subscription
  }

  /** Using fromContact above fetch all the subscriptions for a given contact
    */
  private def subscriptionsForContact(
      transform: SubscriptionTransform.TimeRelativeSubTransformer,
  )(contact: ContactId)(implicit logPrefix: LogPrefix): M[List[Subscription]] =
    for {
      subJsonsEither <- jsonSubscriptionsFromContact(contact)
      catalog <- futureCatalog
    } yield {
      val highLevelSubscriptions = for {
        subJsons <- subJsonsEither.withLogging("sub service - get for contact")
        subscriptions <- transform(catalog, pids)(subJsons).withLogging("sub transform for json")
      } yield subscriptions
      highLevelSubscriptions.toEither match {
        case Left(error) =>
          logger.warn(s"Error from sub service for contact $contact: $error")
          List.empty // returns an empty list if there's an error
        case Right(nel) => nel.toList
      }
    }

  def current(contact: ContactId)(implicit logPrefix: LogPrefix): M[List[Subscription]] =
    subscriptionsForContact(SubscriptionTransform.getCurrentSubscriptions)(contact)

  def since(onOrAfter: LocalDate)(contact: ContactId)(implicit logPrefix: LogPrefix): M[List[Subscription]] =
    subscriptionsForContact(SubscriptionTransform.getSubscriptionsActiveOnOrAfter(onOrAfter))(contact)

  def recentlyCancelled(
      contact: ContactId,
      today: LocalDate = LocalDate.now(),
      lastNMonths: Int = 3, // cancelled in the last N months
  )(implicit logPrefix: LogPrefix): M[String \/ List[Subscription]] =
    (for {
      catalog <- EitherT(futureCatalog.map(\/.right[String, CatalogMap]))
      jsonSubs <- EitherT(jsonSubscriptionsFromContact(contact))
      subs <- EitherT(Monad[M].pure(SubscriptionTransform.getRecentlyCancelledSubscriptions(today, lastNMonths, catalog, pids, jsonSubs)))
    } yield subs).run

  def subscriptionsForAccountId(
      accountId: AccountId,
  )(implicit logPrefix: LogPrefix): M[Disjunction[String, List[Subscription]]] =
    for {
      subJsonsEither <- jsonSubscriptionsFromAccount(accountId)
      catalog <- futureCatalog
    } yield {
      subJsonsEither.rightMap { subJsons =>
        SubscriptionTransform.getCurrentSubscriptions(catalog, pids)(subJsons).toList.flatMap(_.list.toList)
      }
    }

  private def jsonSubscriptionsFromContact(contact: ContactId)(implicit logPrefix: LogPrefix): M[Disjunction[String, List[JsValue]]] =
    (for {
      account <- ListT[EitherTM, AccountId](
        EitherT[String, M, IList[AccountId]](soap.getAccountIds(contact).map(l => \/.r[String](IList.fromSeq(l)))),
      )
      subJson <- ListT[EitherTM, JsValue](EitherT(jsonSubscriptionsFromAccount(account)).map(IList.fromSeq))
    } yield subJson).toList.run

  private def jsonSubscriptionsFromAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): M[Disjunction[String, List[JsValue]]] =
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
  def decideCancellationEffectiveDate(
      subscriptionName: memsub.Subscription.Name,
      wallClockTimeNow: LocalTime = LocalTime.now(),
      today: LocalDate = LocalDate.now(),
  )(implicit logPrefix: LogPrefix): EitherT[String, M, Option[LocalDate]] = {
    EitherT(
      OptionT(get(subscriptionName, isActiveToday = true)).fold(
        zuoraSubscriptionWithCurrentSegment => {
          val paidPlans =
            zuoraSubscriptionWithCurrentSegment.plans.list
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
