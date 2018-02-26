package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.rest.ZuoraRestService.{AccountSummary, PaymentMethodId, PaymentMethodResponse, QueryResponse}
import loghandling.LoggingField.LogField
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models.{Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future
import scalaz.std.list._
import scalaz.std.scalaFuture._
import scalaz.syntax.traverse._
import scalaz.{-\/, Disjunction, EitherT, \/, \/-, _}

object AttributesFromZuora extends LoggingWithLogstashFields {

  type SubscriptionWithAccount = (Option[Subscription[AnyPlan]], AccountSummary)

  def getAttributes(identityId: String,
                    identityIdToAccountIds: String => Future[String \/ QueryResponse],
                    subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]],
                    accountSummaryForAccountId: AccountId => Future[\/[String, AccountSummary]],
                    paymentMethodForPaymentMethodId: PaymentMethodId => Future[\/[String, PaymentMethodResponse]],
                    dynamoAttributeService: AttributeService,
                    forDate: LocalDate = LocalDate.now()): Future[(String, Option[Attributes])] = {


    def twoWeekExpiry = forDate.toDateTimeAtStartOfDay.plusDays(14)

    val attributesFromDynamo: Future[Option[DynamoAttributes]] = dynamoAttributeService.get(identityId) map { attributes =>
      dynamoAttributeService.ttlAgeCheck(attributes, identityId, forDate)
      attributes
    }

    val zuoraAttributesDisjunction: DisjunctionT[Future, String, Future[Option[ZuoraAttributes]]] = for {
      accounts <- EitherT[Future, String, QueryResponse](withTimer(s"ZuoraAccountIdsFromIdentityId", zuoraAccountsQuery(identityId, identityIdToAccountIds), identityId))
      accountIds = queryToAccountIds(accounts)
      accountSummaries <- EitherT[Future, String, List[AccountSummary]](
        if(accountIds.nonEmpty) withTimer(s"ZuoraGetAccountSummaries", getAccountSummaries(accountIds, identityId, accountSummaryForAccountId), identityId)
        else Future.successful(\/.right {
          log.info(s"User with identityId $identityId has no accountIds and thus no account summaries")
          Nil
        })
      )
      subscriptions <- EitherT[Future, String, List[SubscriptionWithAccount]](
        if(accountSummaries.nonEmpty) withTimer(s"ZuoraGetSubscriptions", getSubscriptions(accountSummaries, identityId, subscriptionsForAccountId), identityId)
        else Future.successful(\/.right {
          log.info(s"User with identityId $identityId has no accountIds and thus no subscriptions.")
          Nil
        })
      )
    } yield {
      AttributesMaker.zuoraAttributes(identityId, subscriptions, paymentMethodForPaymentMethodId, forDate)
    }

    val attributesFromZuora = zuoraAttributesDisjunction.run.map { attributesDisjunction: Disjunction[String, Future[Option[ZuoraAttributes]]] =>
      attributesDisjunction.leftMap { errorMsg =>
          log.error(s"Tried to get Attributes for $identityId but failed with $errorMsg")
          errorMsg
        }
      }

    def updateIfNeeded(zuoraAttributes: Option[ZuoraAttributes], dynamoAttributes: Option[DynamoAttributes], attributes: Option[Attributes]) = {
      if(dynamoUpdateRequired(dynamoAttributes, zuoraAttributes, identityId, twoWeekExpiry)) {
        updateCache(identityId, attributes, dynamoAttributeService, twoWeekExpiry).onFailure {
          case e: Throwable =>
            log.error(s"Future failed when attempting to update cache.", e)
            log.warn(s"Future failed when attempting to update cache. Attributes from Zuora: $attributes")
        }
      }
    }

    //return what we know from Dynamo if Zuora returns an error
    val fallbackIfZuoraFails: Future[(String, Option[Attributes])] = attributesFromDynamo map { maybeDynamoAttributes => ("Dynamo", maybeDynamoAttributes map {DynamoAttributes.asAttributes(_)})}

    val attributesFromZuoraUnlessFallback: Future[(String, Option[Attributes])] = attributesFromZuora flatMap {
      case -\/(error) => fallbackIfZuoraFails
      case \/-(maybeZuoraAttributes) => maybeZuoraAttributes flatMap { zuoraAttributes: Option[ZuoraAttributes] =>
        attributesFromDynamo map { dynamoAttributes =>
          val combinedAttributes = AttributesMaker.zuoraAttributesWithAddedDynamoFields(zuoraAttributes, dynamoAttributes)
          updateIfNeeded(zuoraAttributes, dynamoAttributes, combinedAttributes)
          ("Zuora", combinedAttributes)
        }
      }
    }
    // return what we know from Dynamo if the future times out/fails
    val attributesOrFallbackToDynamo: Future[(String, Option[Attributes])] = attributesFromZuoraUnlessFallback fallbackTo fallbackIfZuoraFails

    attributesOrFallbackToDynamo
  }

  def dynamoUpdateRequired(dynamoAttributes: Option[DynamoAttributes], zuoraAttributes: Option[ZuoraAttributes], identityId: String, twoWeekExpiry: => DateTime): Boolean = {

    def ttlUpdateRequired(currentExpiry: DateTime) = twoWeekExpiry.isAfter(currentExpiry.plusDays(1))
    def calculateExpiry(currentExpiry: Option[DateTime]): DateTime = currentExpiry match {
      case Some(expiry) if (ttlUpdateRequired(expiry)) =>
        log.info (s"TTL update required for user $identityId with current expiry $expiry. New expiry is $twoWeekExpiry.")
        twoWeekExpiry
      case Some(expiry) =>
        log.info(s"No TTL update required for user $identityId with current expiry $expiry.")
        expiry
      case None =>
        log.info(s"Record for user $identityId has no TTL so setting TTL to $twoWeekExpiry.")
        twoWeekExpiry
    }

    val currentExpiry: Option[DateTime] = dynamoAttributes.map { attributes => TtlConversions.secondsToDateTime(attributes.TTLTimestamp) }
    val newExpiry: DateTime = calculateExpiry(currentExpiry)

    def expiryShouldChange(dynamoAttributes: Option[DynamoAttributes], currentExpiry: Option[DateTime], newExpiry: DateTime) = dynamoAttributes.isDefined && !currentExpiry.contains(newExpiry)

    expiryShouldChange(dynamoAttributes, currentExpiry, newExpiry) || !dynamoAndZuoraAgree(dynamoAttributes, zuoraAttributes, identityId)
  }

  private def updateCache(identityId: String, maybeAttributes: Option[Attributes], dynamoAttributeService: AttributeService, twoWeekExpiry: => DateTime): Future[Unit] = {
    val attributesWithTTL: Option[DynamoAttributes] = maybeAttributes map { attributes =>
        DynamoAttributes(
        attributes.UserId,
        attributes.Tier,
        attributes.RecurringContributionPaymentPlan,
        attributes.MembershipJoinDate,
        attributes.DigitalSubscriptionExpiryDate,
        attributes.MembershipNumber,
        attributes.AdFree,
        TtlConversions.toDynamoTtlInSeconds(twoWeekExpiry)
      )
    }

    attributesWithTTL match {
      case Some(attributes) =>
        dynamoAttributeService.update(attributes).map { result =>
          result.left.map { error: DynamoReadError =>
            log.warn(s"Tried updating attributes for $identityId but then ${DynamoReadError.describe(error)}")
            log.error("Tried updating attributes with updated values from Zuora but there was a dynamo error.")
          }
        }
      case None =>
        dynamoAttributeService.delete(identityId) map { result =>
          log.info(s"Deleting the dynamo entry for $identityId. $result")
        }
    }
  }

  def getSubscriptions(accountSummaries: List[AccountSummary],
                       identityId: String,
                       subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]]): Future[Disjunction[String, List[SubscriptionWithAccount]]] = {

    def subWithAccount(accountSummary: AccountSummary)(implicit reads: SubPlanReads[AnyPlan]): Future[Disjunction[String, (Option[Subscription[AnyPlan]], AccountSummary)]] = subscriptionsForAccountId(accountSummary.id)(anyPlanReads) map { maybeSub =>
       maybeSub map {subList => (subList.headOption, accountSummary)}
    }

    val maybeSubs: Future[Disjunction[String, List[SubscriptionWithAccount]]] = accountSummaries.traverse[Future, Disjunction[String, SubscriptionWithAccount]](summary => {
      subWithAccount(summary)(anyPlanReads)
    }).map(_.sequenceU)

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

  def getAccountSummaries(accountIds: List[AccountId],
                       identityId: String,
                       accountSummaryForAccountId: AccountId => Future[Disjunction[String, AccountSummary]]): Future[Disjunction[String, List[AccountSummary]]] = {

    val maybeAccountSummaries = accountIds.traverse[Future, Disjunction[String, AccountSummary]](id => {
      accountSummaryForAccountId(id)
    }).map(_.sequenceU)    //todo generalise me?


    maybeAccountSummaries.map {
      _.leftMap { errorMsg =>
        log.warn(s"We tried getting account summaries for a user with identityId $identityId, but then $errorMsg")
        s"We called Zuora to get account summaries for a user with identityId $identityId but the call failed because $errorMsg"
      }
    }
  }

  private def withTimer[R](whichCall: String, futureResult: Future[Disjunction[String, R]], identityId: String): Future[Disjunction[String, R]] = {
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

  def dynamoAndZuoraAgree(maybeDynamoAttributes: Option[DynamoAttributes], maybeZuoraAttributes: Option[ZuoraAttributes], identityId: String): Boolean = {
    val dynamoAttributesForComparison: Option[ZuoraAttributes] = maybeDynamoAttributes map { dynamoAttributes =>
      ZuoraAttributes(
        UserId = dynamoAttributes.UserId,
        Tier = dynamoAttributes.Tier,
        RecurringContributionPaymentPlan = dynamoAttributes.RecurringContributionPaymentPlan,
        MembershipJoinDate = dynamoAttributes.MembershipJoinDate,
        DigitalSubscriptionExpiryDate = dynamoAttributes.DigitalSubscriptionExpiryDate
      )
    }

    val dynamoAndZuoraAgree = dynamoAttributesForComparison == maybeZuoraAttributes

    if (!dynamoAndZuoraAgree)
      log.info(s"We looked up attributes via Zuora for $identityId and Zuora and Dynamo disagreed." +
        s" Zuora attributes: $maybeZuoraAttributes, parsed as: $dynamoAttributesForComparison. Dynamo attributes: $maybeDynamoAttributes.")

    dynamoAndZuoraAgree
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

