package com.gu.memsub.subsv2.services

import com.gu.memsub.Subscription.{ProductRatePlanId, RatePlanId}
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads.ProductIds
import com.gu.memsub.subsv2.reads.CommonReads._
import com.gu.memsub.subsv2.reads.SubJsonReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import org.joda.time.LocalDate
import play.api.libs.json.{Reads => JsReads, _}
import scalaz._
import scalaz.syntax.all._
import scalaz.syntax.std.either._
import scalaz.syntax.std.option._

import scala.util.Try

// this is (all?) the testable stuff without mocking needed
// we should make the subscription service just getting the json, and then we can have testable pure functions here
object SubscriptionTransform extends SafeLogging {

  val subIdsReads: JsReads[SubIds] = new JsReads[SubIds] {
    override def reads(json: JsValue): JsResult[SubIds] = {

      (
        (json \ "id").validate[String].map(RatePlanId) |@|
          (json \ "productRatePlanId").validate[String].map(ProductRatePlanId)
      )(SubIds)
    }
  }

  type TimeRelativeSubTransformer = (CatalogMap, ProductIds) => List[JsValue] => Disjunction[String, NonEmptyList[Subscription]]

  def getCurrentSubscriptions(catalog: CatalogMap, pids: ProductIds)(
      subJsons: List[JsValue],
  ): Disjunction[String, NonEmptyList[Subscription]] = {

    def getFirstCurrentSub(
        subs: NonEmptyList[Subscription],
    ): String \/ NonEmptyList[Subscription] = // just quickly check to find one with a current plan
      Sequence(
        subs
          .map { sub =>
            Try {
              sub.plan // just to force a throw if it doesn't have one
            } match {
              case scala.util.Success(_) => \/-(sub): \/[String, Subscription]
              case scala.util.Failure(ex) => -\/(ex.toString): \/[String, Subscription]
            }
          }
          .list
          .toList,
      )

    Sequence(subJsons.map { subJson =>
      getSubscription(catalog, pids)(subJson)
    }).flatMap(getFirstCurrentSub)
  }

  def getSubscriptionsActiveOnOrAfter(
      onOrAfter: LocalDate,
  )(catalog: CatalogMap, pids: ProductIds)(subJsons: List[JsValue]): Disjunction[String, NonEmptyList[Subscription]] =
    Sequence(subJsons.map(getSubscription(catalog, pids)).filter {
      case \/-(sub) => !sub.termEndDate.isBefore(onOrAfter)
      case _ => false
    })

  def getRecentlyCancelledSubscriptions(
      today: LocalDate,
      lastNMonths: Int, // cancelled in the last n months
      catalog: CatalogMap,
      pids: ProductIds,
      subJsons: List[JsValue],
  ): Disjunction[String, List[Subscription]] = {
    import Scalaz._
    subJsons
      .map(getSubscription(catalog, pids))
      .sequence
      .map {
        _.filter { sub =>
          sub.isCancelled &&
          (sub.termEndDate isAfter today.minusMonths(lastNMonths)) &&
          (sub.termEndDate isBefore today)
        }
      }
  }

  def getSubscription(
      catalog: CatalogMap,
      pids: ProductIds,
      now: () => LocalDate = LocalDate.now, /*now only needed for pending friend downgrade*/
  )(subJson: JsValue): Disjunction[String, Subscription] = {
    import Trace.Traceable
    val planToSubscriptionFunction =
      subscriptionReads(now()).reads(subJson).asEither.toDisjunction.leftMap(_.mkString(" ")).withTrace("planToSubscriptionFunction")

    val lowLevelPlans = subJson
      .validate[List[SubscriptionZuoraPlan]](subZuoraPlanListReads)
      .asEither
      .toDisjunction
      .leftMap(_.toString)
      .withTrace("validate-lowLevelPlans")
    lowLevelPlans.flatMap { lowLevelPlans =>
      val validHighLevelPlans: String \/ NonEmptyList[SubscriptionPlan] =
        Sequence(
          lowLevelPlans
            .map { lowLevelPlan =>
              // get the equivalent plan from the catalog so we can merge them into a standard high level object
              catalog
                .get(lowLevelPlan.productRatePlanId)
                .toRightDisjunction(s"No catalog plan - prpId = ${lowLevelPlan.productRatePlanId}")
                .flatMap { catalogPlan =>
                  val maybePlans = SubPlanReads.anyPlanReads(pids, lowLevelPlan, catalogPlan)
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
