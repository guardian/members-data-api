package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.zuora.ZuoraRestService.QueryResponse
import loghandling.LoggingField.LogField
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models.Attributes
import org.joda.time.LocalDate
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future
import scalaz.std.list._
import scalaz.std.scalaFuture._
import scalaz.syntax.traverse._
import scalaz.{-\/, Disjunction, EitherT, \/, \/-, _}


object AttributesFromZuora extends LoggingWithLogstashFields {

  def getAttributes(identityId: String,
                    identityIdToAccountIds: String => Future[String \/ QueryResponse],
                    subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]],
                    dynamoAttributeGetter: String => Future[Option[Attributes]]): Future[Option[Attributes]] = {

    val attributesDisjunction = for {
      accounts <- EitherT[Future, String, QueryResponse](withTimer(s"ZuoraAccountIdsFromIdentityId", zuoraAccountsQuery(identityId, identityIdToAccountIds), identityId))
      accountIds = queryToAccountIds(accounts)
      subscriptions <- EitherT[Future, String, List[Subscription[AnyPlan]]](
        if(accountIds.nonEmpty) withTimer(s"ZuoraGetSubscriptions", getSubscriptions(accountIds, identityId, subscriptionsForAccountId), identityId)
        else Future.successful(\/.right {
          log.info(s"User with identityId $identityId has no accountIds and thus no subscriptions.")
          Nil
        })
      )
    } yield {
      AttributesMaker.attributes(identityId, subscriptions, LocalDate.now())
    }

    val attributes = attributesDisjunction.run.map {
      _.leftMap { errorMsg =>
        log.error(s"Tried to get Attributes for $identityId but failed with $errorMsg")
        errorMsg
      }.fold(_ => None, identity)
    }

    val attributesFromDynamo: Future[Option[Attributes]] = dynamoAttributeGetter(identityId)

    dynamoAndZuoraAgree(attributesFromDynamo, attributes, identityId)

    attributesWithFlagFromDynamo(attributes, attributesFromDynamo)
  }


  def attributesWithFlagFromDynamo(attributesFromZuora: Future[Option[Attributes]], attributesFromDynamo: Future[Option[Attributes]]) = {
    val adFreeFlagFromDynamo = attributesFromDynamo map (_.flatMap(_.AdFree))

    attributesFromZuora flatMap { maybeAttributes =>
      adFreeFlagFromDynamo map { adFree =>
        maybeAttributes map {_.copy(AdFree = adFree)}
      }
    }
  }


  def getSubscriptions(accountIds: List[AccountId],
                       identityId: String,
                       subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]]): Future[Disjunction[String, List[Subscription[AnyPlan]]]] = {

    def sub(accountId: AccountId): Future[Disjunction[String, List[Subscription[AnyPlan]]]] = subscriptionsForAccountId(accountId)(anyPlanReads)

    val maybeSubs: Future[Disjunction[String, List[Subscription[AnyPlan]]]] = accountIds.traverse[Future, Disjunction[String, List[Subscription[AnyPlan]]]](id => {
      sub(id)
    }).map(_.sequenceU.map(_.flatten))

    maybeSubs.map {
      _.leftMap { errorMsg =>
        log.warn(s"We tried getting subscription for a user with identityId $identityId, but then $errorMsg")
        s"We called Zuora to get subscriptions for a user with identityId $identityId but the call failed because $errorMsg"
      } map { subs =>
        log.info(s"We got subs for identityId $identityId from Zuora and there were ${subs.length}")
        subs
      }
    }
  }

  private def withTimer[R](whichCall: String, futureResult: Future[Disjunction[String, R]], identityId: String) = {
    import loghandling.StopWatch
    val stopWatch = new StopWatch

    futureResult.map { disjunction: Disjunction[String, R] =>
      disjunction match {
        case -\/(message) => log.warn(s"$whichCall failed with: $message")
        case \/-(_) =>
          val latency = stopWatch.elapsed
          val zuoraConcurrencyCount = ZuoraRequestCounter.get
          val customFields: List[LogField] = List("zuora_latency_millis" -> latency.toInt, "zuora_call" -> whichCall, "identityId" -> identityId, "zuora_concurrency_count" -> zuoraConcurrencyCount)
          logInfoWithCustomFields(s"$whichCall took ${latency}ms.", customFields)
      }
    }.onFailure {
      case e: Throwable => log.error(s"Future failed when attempting $whichCall.", e)
    }
    futureResult
  }

  def dynamoAndZuoraAgree(attributesFromDynamo: Future[Option[Attributes]], attributesFromZuora: Future[Option[Attributes]], identityId: String): Future[Boolean] = {
    attributesFromDynamo flatMap { maybeDynamoAttributes =>
      attributesFromZuora map { maybeZuoraAttributes =>
        val zuoraAttributesWithIgnoredFields = maybeZuoraAttributes flatMap  { zuoraAttributes =>
          maybeDynamoAttributes map { dynamoAttributes =>
            zuoraAttributes.copy(
              AdFree = dynamoAttributes.AdFree, //fetched from Dynamo in the Zuora lookup anyway (dynamo is the source of truth)
              Wallet = dynamoAttributes.Wallet, //can't be found based on Zuora lookups, and not currently used
              MembershipNumber = dynamoAttributes.MembershipNumber, //I don't think membership number is needed and it comes from Salesforce
              MembershipJoinDate = dynamoAttributes.MembershipJoinDate.flatMap(_ => zuoraAttributes.MembershipJoinDate), //only compare if dynamo has value
              DigitalSubscriptionExpiryDate = None
            )
          }
        }
        val dynamoAndZuoraAgree = zuoraAttributesWithIgnoredFields == maybeDynamoAttributes
        if (!dynamoAndZuoraAgree)
          log.info(s"We looked up attributes via Zuora for $identityId and Zuora and Dynamo disagreed." +
            s" Zuora attributes: $maybeZuoraAttributes. Dynamo attributes: $maybeDynamoAttributes.")

        dynamoAndZuoraAgree
      }
    }
  }

  def queryToAccountIds(response: QueryResponse): List[AccountId] =  response.records.map(_.Id)

  def zuoraAccountsQuery(identityId: String, identityIdToAccountIds: String => Future[String \/ QueryResponse]): Future[Disjunction[String, QueryResponse]] =
    identityIdToAccountIds(identityId).map {
      _.leftMap { error =>
        log.warn(s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId. with error: ${error}")
        s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId."
    }
  }
}

