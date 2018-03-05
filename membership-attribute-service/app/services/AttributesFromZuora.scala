package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, AccountSummary, GetAccountsQueryResponse, PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingField.LogField
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models.{AccountWithSubscription, Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scalaz.std.list._
import scalaz.std.scalaFuture._
import scalaz.syntax.traverse._
import scalaz.{-\/, Disjunction, DisjunctionT, EitherT, \/, \/-}

class AttributesFromZuora(implicit val executionContext: ExecutionContext) extends LoggingWithLogstashFields {

  def getAttributes(
     identityId: String,
     identityIdToAccountIds: String => Future[String \/ GetAccountsQueryResponse],
     subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]],
     paymentMethodForPaymentMethodId: PaymentMethodId => Future[\/[String, PaymentMethodResponse]],
     dynamoAttributeService: AttributeService,
     forDate: LocalDate = LocalDate.now()): Future[(String, Option[Attributes])] = {

    def twoWeekExpiry = forDate.toDateTimeAtStartOfDay.plusDays(14)

    val attributesFromDynamo: Future[Option[DynamoAttributes]] = dynamoAttributeService.get(identityId) map { attributes =>
      dynamoAttributeService.ttlAgeCheck(attributes, identityId, forDate)
      attributes
    }

    def getResultIf[R](shouldCallFunction: Boolean, whichCall: String, futureToExecute: () => Future[Disjunction[String, R]], default: R, identityId: String): Future[Disjunction[String, R]] = {
      if(shouldCallFunction) {
        withTimer(whichCall, futureToExecute, identityId)
      } else Future.successful(\/.right {
        log.info(s"User with identityId $identityId has no accountIds/account summaries and so $whichCall is not needed.")
        default
      })
    }

    val zuoraAttributesDisjunction: DisjunctionT[Future, String, Future[Option[ZuoraAttributes]]] = for {
      accounts <- EitherT[Future, String, GetAccountsQueryResponse](withTimer(s"ZuoraAccountIdsFromIdentityId", () => zuoraAccountsQuery(identityId, identityIdToAccountIds), identityId))
      subscriptions <- EitherT[Future, String, List[AccountWithSubscription]](getResultIf(accounts.size > 0, "ZuoraGetSubscriptions", () => getSubscriptions(accounts, identityId, subscriptionsForAccountId), Nil, identityId))
    } yield {
      AttributesMaker.zuoraAttributes(identityId, subscriptions, paymentMethodForPaymentMethodId, forDate)
    }

    val attributesFromZuora = zuoraAttributesDisjunction.run.map { attributesDisjunction: Disjunction[String, Future[Option[ZuoraAttributes]]] =>
      attributesDisjunction.leftMap { errorMsg =>
          log.error(s"Tried to get Attributes for $identityId but failed with $errorMsg")
          errorMsg
        }
      }

    def updateIfNeeded(zuoraAttributes: Option[ZuoraAttributes], dynamoAttributes: Option[DynamoAttributes], attributes: Option[Attributes]): Unit = {
      if(dynamoUpdateRequired(dynamoAttributes, zuoraAttributes, identityId, twoWeekExpiry)) {
        updateCache(identityId, attributes, dynamoAttributeService, twoWeekExpiry).onFailure {
          case e: Throwable =>
            log.error(s"Future failed when attempting to update cache.", e)
            log.warn(s"Future failed when attempting to update cache. Attributes from Zuora: $attributes")
        }
      }
    }

    //return what we know from Dynamo if Zuora returns an error
    val fallbackIfZuoraFails: Future[(String, Option[Attributes])] = attributesFromDynamo map { maybeDynamoAttributes => ("Dynamo", maybeDynamoAttributes map DynamoAttributes.asAttributes)}

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
      case Some(expiry) if ttlUpdateRequired(expiry) =>
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

  def getSubscriptions(
    getAccountsResponse: GetAccountsQueryResponse,
    identityId: String,
    subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]]): Future[Disjunction[String, List[AccountWithSubscription]]] = {

    def subWithAccount(account: AccountObject)(implicit reads: SubPlanReads[AnyPlan]): Future[Disjunction[String, AccountWithSubscription]] = subscriptionsForAccountId(account.Id)(anyPlanReads) map { maybeSub =>
       maybeSub map {subList => AccountWithSubscription(account, subList.headOption)}
    }

    val maybeSubs: Future[Disjunction[String, List[AccountWithSubscription]]] = getAccountsResponse.records.traverse[Future, Disjunction[String, AccountWithSubscription]](accountObject => {
      subWithAccount(accountObject)(anyPlanReads)
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

  def getAccountSummaries(
    accountIds: List[AccountId],
    identityId: String,
    accountSummaryForAccountId: AccountId => Future[Disjunction[String, AccountSummary]]): Future[Disjunction[String, List[AccountSummary]]] = {

    val maybeAccountSummaries = accountIds.traverse[Future, Disjunction[String, AccountSummary]](id => {
      accountSummaryForAccountId(id)
    }).map(_.sequenceU)


    maybeAccountSummaries.map {
      _.leftMap { errorMsg =>
        log.warn(s"We tried getting account summaries for a user with identityId $identityId, but then $errorMsg")
        s"We called Zuora to get account summaries for a user with identityId $identityId but the call failed because $errorMsg"
      }
    }
  }

  private def withTimer[R](whichCall: String, executeFuture: () => Future[Disjunction[String, R]], identityId: String): Future[Disjunction[String, R]] = {
    import loghandling.StopWatch
    val stopWatch = new StopWatch

    val futureResult = executeFuture()
    futureResult.onComplete {
      case Success(-\/(message)) => log.warn(s"$whichCall failed with: $message")
      case Success(\/-(_)) =>
        val latency = stopWatch.elapsed
        val zuoraConcurrencyCount = ZuoraRequestCounter.get
        val customFields: List[LogField] = List("zuora_latency_millis" -> latency.toInt, "zuora_call" -> whichCall, "identity_id" -> identityId, "zuora_concurrency_count" -> zuoraConcurrencyCount)
        logInfoWithCustomFields(s"$whichCall took ${latency}ms.", customFields)
      case Failure(e) => log.error(s"Future failed when attempting $whichCall.", e)
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

  def queryToAccountIds(response: GetAccountsQueryResponse): List[AccountId] =  response.records.map(_.Id)

  def zuoraAccountsQuery(identityId: String, identityIdToAccountIds: String => Future[String \/ GetAccountsQueryResponse]): Future[Disjunction[String, GetAccountsQueryResponse]] =
    identityIdToAccountIds(identityId).map {
      _.leftMap { error =>
        log.warn(s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId. with error: $error")
        s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId."
    }
  }
}

