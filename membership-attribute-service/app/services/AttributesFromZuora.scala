package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, AccountSummary, GetAccountsQueryResponse, PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingField.LogField
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models._
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

    val attributesFromZuora: EitherT[Future, String, Option[ZuoraAttributes]] = (for {
      accounts        <- EitherT(zuoraAccountsQuery(identityId, identityIdToAccountIds))
      subscriptions   <- EitherT(if (accounts.size > 0) getSubscriptions(accounts, identityId, subscriptionsForAccountId) else Future.successful(\/.right[String, List[AccountWithSubscriptions]](Nil)))
      maybeAttributes <- EitherT(AttributesMaker.zuoraAttributes(identityId, subscriptions, paymentMethodForPaymentMethodId, forDate).map(\/.right[String, Option[ZuoraAttributes]]))
    } yield maybeAttributes).leftMap { errorMsg =>
      SafeLogger.error(scrub"Tried to get Attributes for $identityId but failed with $errorMsg")
      errorMsg
    }

    def updateIfNeeded(zuoraAttributes: Option[ZuoraAttributes], dynamoAttributes: Option[DynamoAttributes], attributes: Option[Attributes]): Unit = {
      if(dynamoUpdateRequired(dynamoAttributes, zuoraAttributes, identityId, twoWeekExpiry)) {
        log.info(s"Attempting to update cache for $identityId ...")
        updateCache(identityId, attributes, dynamoAttributeService, twoWeekExpiry) onComplete {
          case Success(_) =>
            log.info(s"updated cache for $identityId")
          case Failure(e) =>
            SafeLogger.error(scrub"Future failed when attempting to update cache.", e)
            log.warn(s"Future failed when attempting to update cache. Attributes from Zuora: $attributes")
        }
      }
    }

    //return what we know from Dynamo if Zuora returns an error
    val fallbackIfZuoraFails: Future[(String, Option[Attributes])] = attributesFromDynamo map { maybeDynamoAttributes => ("Dynamo", maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_)))}

    val attributesFromZuoraUnlessFallback: Future[(String, Option[Attributes])] = attributesFromZuora.run.flatMap {
      case -\/(error) => fallbackIfZuoraFails
      case \/-(maybeZuoraAttributes) =>
        val maybeAttributes = maybeZuoraAttributes.map(ZuoraAttributes.asAttributes(_))
        attributesFromDynamo.foreach(updateIfNeeded(maybeZuoraAttributes, _, maybeAttributes))
        Future.successful("Zuora", maybeAttributes)
    }

    // return what we know from Dynamo if the future times out/fails
    attributesFromZuoraUnlessFallback fallbackTo fallbackIfZuoraFails

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

    def expiryShouldChange(dynamoAttributes: Option[DynamoAttributes], currentExpiry: Option[DateTime], newExpiry: DateTime) =
      dynamoAttributes.isDefined && !currentExpiry.contains(newExpiry)

    expiryShouldChange(dynamoAttributes, currentExpiry, newExpiry) || !dynamoAndZuoraAgree(dynamoAttributes, zuoraAttributes, identityId)
  }

  private def updateCache(identityId: String, maybeAttributes: Option[Attributes], dynamoAttributeService: AttributeService, twoWeekExpiry: => DateTime): Future[Unit] = {
    val attributesWithTTL: Option[DynamoAttributes] = maybeAttributes map { attributes =>
      DynamoAttributes(
        UserId = attributes.UserId,
        Tier = attributes.Tier,
        RecurringContributionPaymentPlan = attributes.RecurringContributionPaymentPlan,
        MembershipJoinDate = attributes.MembershipJoinDate,
        DigitalSubscriptionExpiryDate = attributes.DigitalSubscriptionExpiryDate,
        PaperSubscriptionExpiryDate = attributes.PaperSubscriptionExpiryDate,
        GuardianWeeklySubscriptionExpiryDate = attributes.GuardianWeeklySubscriptionExpiryDate,
        TTLTimestamp = TtlConversions.toDynamoTtlInSeconds(twoWeekExpiry)
      )
    }

    attributesWithTTL match {
      case Some(attributes) =>
        dynamoAttributeService.update(attributes).map { result =>
          result.left.map { error: DynamoReadError =>
            log.warn(s"Tried updating attributes for $identityId but then ${DynamoReadError.describe(error)}")
            SafeLogger.error(scrub"Tried updating attributes with updated values from Zuora but there was a dynamo error.")
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
    subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]]
  ): Future[Disjunction[String, List[AccountWithSubscriptions]]] = {

    def accountWithSubscriptions(account: AccountObject)(implicit reads: SubPlanReads[AnyPlan]): Future[Disjunction[String, AccountWithSubscriptions]] = {
      subscriptionsForAccountId(account.Id)(anyPlanReads).map { maybeSub =>
        maybeSub.map { subList =>
          AccountWithSubscriptions(account, subList)
        }
      }
    }

    val maybeSubs: Future[Disjunction[String, List[AccountWithSubscriptions]]] = getAccountsResponse.records.traverse[Future, Disjunction[String, AccountWithSubscriptions]](accountObject => {
      accountWithSubscriptions(accountObject)(anyPlanReads)
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

  private def getAccountSummaries(
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

  //if Zuora attributes have changed at all, we should update the cache even if the ttl is not stale
  def dynamoAndZuoraAgree(maybeDynamoAttributes: Option[DynamoAttributes], maybeZuoraAttributes: Option[ZuoraAttributes], identityId: String): Boolean = {

    val zuoraAttributesAsAttributes = maybeZuoraAttributes map (ZuoraAttributes.asAttributes(_))
    val dynamoAttributesAsAttributes = maybeDynamoAttributes map (DynamoAttributes.asAttributes(_))

    val dynamoAndZuoraAgree = dynamoAttributesAsAttributes == zuoraAttributesAsAttributes

    if (!dynamoAndZuoraAgree)
      log.info(s"We looked up attributes via Zuora for $identityId and Zuora and Dynamo disagreed." +
        s" Zuora attributes as attributes: $zuoraAttributesAsAttributes. Dynamo attributes as attributes: $dynamoAttributesAsAttributes.")
    else if (zuoraAttributesAsAttributes.isEmpty) {
      log.info(s"There are no Zuora attributes for $identityId")
    }

    dynamoAndZuoraAgree
  }

  def queryToAccountIds(response: GetAccountsQueryResponse): List[AccountId] =  response.records.map(_.Id)

  private def zuoraAccountsQuery(
    identityId: String,
    identityIdToAccountIds: String => Future[String \/ GetAccountsQueryResponse]
  ): Future[Disjunction[String, GetAccountsQueryResponse]] =
    identityIdToAccountIds(identityId).map {
      _.leftMap { error =>
        log.warn(s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId. with error: $error")
        s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId."
    }
  }
}

