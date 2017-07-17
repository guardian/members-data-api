package services

import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.Subscription
import com.gu.zuora.ZuoraRestService
import com.gu.zuora.ZuoraRestService.QueryResponse
import loghandling.LoggingField.LogField
import loghandling.LoggingWithLogstashFields
import models.Attributes
import org.joda.time.LocalDate

import scala.concurrent.Future
import scalaz.{-\/, Disjunction, DisjunctionT, EitherT, \/, \/-}

class ZuoraAttributeService(zuoraRestService: ZuoraRestService[Future], subscriptionService: SubscriptionService[Future]) extends LoggingWithLogstashFields {

  def getAttributes(identityId: String): Future[Option[Attributes]] = {
    def queryToAccountIds(response: QueryResponse): List[AccountId] =  response.records.map(_.Id)

    def getSubscriptions(accountIds: List[AccountId]): Future[Disjunction[String, List[Subscription[AnyPlan]]]] = {
      def sub(accountId: AccountId): Future[List[Subscription[AnyPlan]]] = {
        subscriptionService.subscriptionsForAccountId[AnyPlan](accountId)(anyPlanReads)
      }
      val maybeSubs: Future[List[Subscription[AnyPlan]]] = Future.traverse(accountIds)(id => sub(id)).map(_.flatten)

      maybeSubs map { subs =>
        if (subs.isEmpty) \/.left(s"Error getting subscriptions for identityId $identityId") else \/.right(subs)
      }
    }

    val attributes: DisjunctionT[Future, String, Option[Attributes]] = for {
      accounts <- EitherT(withZuoraLatencyLogged(s"ZuoraAccountIdsFromIdentityId", zuoraRestService.getAccounts(identityId), identityId))
      accountIds = queryToAccountIds(accounts)
      subscriptions <- EitherT[Future, String, List[Subscription[AnyPlan]]](withZuoraLatencyLogged(s"ZuoraGetSubscriptions", getSubscriptions(accountIds), identityId))
    } yield {
      AttributesMaker.attributes(identityId, subscriptions, LocalDate.now())
    }
    attributes.run.map(_.toOption).map(_.getOrElse(None))
  }


  def withZuoraLatencyLogged[R](whichCall: String, result: Future[Disjunction[String, R]], identityId: String) = {
    import loghandling.StopWatch
    val stopWatch = new StopWatch

    result.map { res: Disjunction[String, R] =>
      val latency = stopWatch.elapsed
      val customFields: List[LogField] = List("zuora_latency_millis" -> latency.toInt, "zuora_call" -> whichCall, "identityId" -> identityId)
      res match {
        case -\/(a) => logErrorWithCustomFields(s"$whichCall failed. Msg: ${a}", customFields)
        case \/-(_) => logInfoWithCustomFields(s"$whichCall took ${latency}ms", customFields)
      }
    }
    result
  }


}
