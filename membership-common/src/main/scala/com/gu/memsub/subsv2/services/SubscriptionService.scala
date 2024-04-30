package com.gu.memsub.subsv2.services

import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub.subsv2.SubscriptionPlan._
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads.ProductIds
import com.gu.memsub.subsv2.reads.SubJsonReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.monitoring.SafeLogging
import org.joda.time.LocalDate
import play.api.libs.json._
import scalaz._
import scalaz.syntax.std.either._
import scalaz.syntax.std.option._

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

import com.gu.memsub.subsv2.services.Trace.Traceable

// this is (all?) the testable stuff without mocking needed
// we should make the subscription service just getting the json, and then we can have testable pure functions here
object SubscriptionTransform extends SafeLogging {

  def getRecentlyCancelledSubscriptions[P <: AnyPlan: SubPlanReads](
      today: LocalDate,
      lastNMonths: Int, // cancelled in the last n months
      catalog: CatalogMap,
      pids: ProductIds,
      subJsons: List[JsValue],
  ): Disjunction[String, List[Subscription[P]]] = {
    import Scalaz._
    subJsons
      .map(getSubscription[P](catalog, pids))
      .sequence
      .map {
        _.filter { sub =>
          sub.isCancelled &&
          (sub.termEndDate isAfter today.minusMonths(lastNMonths)) &&
          (sub.termEndDate isBefore today)
        }
      }
  }

  def getSubscription[P <: AnyPlan: SubPlanReads](
      catalog: CatalogMap,
      pids: ProductIds,
      now: () => LocalDate = LocalDate.now, /*now only needed for pending friend downgrade*/
  )(subJson: JsValue): Disjunction[String, Subscription[P]] = {
    val planToSubscriptionFunction =
      subscriptionReads[P](now()).reads(subJson).asEither.disjunction.leftMap(_.mkString(" ")).withTrace("planToSubscriptionFunction")

    val lowLevelPlans = subJson
      .validate[List[SubscriptionZuoraPlan]](subZuoraPlanListReads)
      .asEither
      .disjunction
      .leftMap(_.toString)
      .withTrace("validate-lowLevelPlans")
    lowLevelPlans.flatMap { lowLevelPlans =>
      val validHighLevelPlans: String \/ NonEmptyList[P] =
        Sequence(
          lowLevelPlans
            .map { lowLevelPlan =>
              // get the equivalent plan from the catalog so we can merge them into a standard high level object
              catalog
                .get(lowLevelPlan.productRatePlanId)
                .toRightDisjunction(s"No catalog plan - prpId = ${lowLevelPlan.productRatePlanId}")
                .flatMap { catalogPlan =>
                  val maybePlans = implicitly[SubPlanReads[P]].read(pids, lowLevelPlan, catalogPlan)
                  maybePlans.toDisjunction
                    .leftMap(
                      _.list.zipWithIndex
                        .map { case (err, index) =>
                          s"  ${index + 1}: $err"
                        }
                        .toList
                        .mkString("\n", "\n", "\n"),
                    )
                    .withTrace(s"high-level-plan-read: ${lowLevelPlan.id}")
                }
            },
        )

      // now wrap them in a subscription
      validHighLevelPlans.flatMap(highLevelPlans => planToSubscriptionFunction.map(_.apply(highLevelPlans)))
    }
  }

}
