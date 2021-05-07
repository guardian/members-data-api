package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, GetAccountsQueryResponse, GiftSubscriptionsFromIdentityIdRecord, GiftSubscriptionsFromIdentityIdResponse, PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingWithLogstashFields
import models._
import org.joda.time.{DateTime, LocalDate}
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scalaz.std.list._
import scalaz.std.scalaFuture._
import scalaz.syntax.traverse._
import scalaz.{Disjunction, EitherT, \/}
import akka.actor.{ActorSystem, Scheduler}
import com.gu.memsub.subsv2.ReaderType.Gift
import com.typesafe.scalalogging.LazyLogging
import monitoring.{ExpensiveMetrics, Metrics}
import services.AttributesFromZuora.{alertIfAttributesDoNotMatch, mergeDigitalSubscriptionExpiryDate}
import services.AttributesMaker.getSubsWhichIncludeDigitalPack
import utils.FutureRetry._

class AttributesFromZuora(implicit val executionContext: ExecutionContext, system: ActorSystem) extends LoggingWithLogstashFields {

  lazy val expensiveMetrics = new ExpensiveMetrics("AttributesFromZuora")

  def getAttributesFromZuoraWithCacheFallback(
     identityId: String,
     identityIdToAccounts: String => Future[String \/ GetAccountsQueryResponse],
     subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]],
     giftSubscriptionsForIdentityId: String => Future[\/[String, List[GiftSubscriptionsFromIdentityIdRecord]]],
     paymentMethodForPaymentMethodId: PaymentMethodId => Future[\/[String, PaymentMethodResponse]],
     dynamoAttributeService: AttributeService,
     supporterProductDataService: SupporterProductDataService,
     forDate: LocalDate = LocalDate.now(),
  ): Future[(String, Option[Attributes])] = {

    def maybeUpdateCache(zuoraAttributes: Option[ZuoraAttributes], dynamoAttributes: Option[DynamoAttributes], attributes: Option[Attributes]): Unit = {
      def twoWeekExpiry = forDate.toDateTimeAtStartOfDay.plusDays(14)
      if(dynamoUpdateRequired(dynamoAttributes, zuoraAttributes, identityId, twoWeekExpiry)) {
        log.info(s"Attempting to update cache for $identityId ...")
        expensiveMetrics.countRequest("cache-update")
        updateCache(identityId, attributes, dynamoAttributeService, twoWeekExpiry) onComplete {
          case Success(_) =>
            log.info(s"updated cache for $identityId")
          case Failure(e) =>
            SafeLogger.error(scrub"Future failed when attempting to update cache.", e)
            log.warn(s"Future failed when attempting to update cache. Attributes from Zuora: $attributes")
        }
      }
    }

    val attributesFromDynamo = // eagerly prepare cache
      dynamoAttributeService
        .get(identityId)
        .andThen {
          case Success(attrib) => dynamoAttributeService.ttlAgeCheck(attrib, identityId, forDate)
          case Failure(e) => SafeLogger.error(scrub"Failed to retrieve attributes from cache for $identityId because $e")
        }

    def userHasDigiSub(accountsWithSubscriptions: List[AccountWithSubscriptions]) =
      getSubsWhichIncludeDigitalPack(
        accountsWithSubscriptions.flatMap(_.subscriptions),
        LocalDate.now()
      ).nonEmpty

    val futureSupporterProductDataAttributes = EitherT(supporterProductDataService.getAttributes(identityId))

    lazy val getAttrFromZuora =
      for {
        accounts <- EitherT(identityIdToAccounts(identityId))
        subscriptions <- EitherT(
          if (accounts.size > 0) getSubscriptions(accounts, subscriptionsForAccountId)
          else Future.successful(\/.right[String, List[AccountWithSubscriptions]](Nil))
        )
        giftSubscriptions <- EitherT(
          if(userHasDigiSub(subscriptions)) Future.successful(\/.right[String, List[GiftSubscriptionsFromIdentityIdRecord]](Nil))
          else giftSubscriptionsForIdentityId(identityId)
        )
        maybeGifteeAttributes = Some(giftSubscriptions.map(_.TermEndDate))
          .filter(_.nonEmpty)
          .map(_.maxBy(_.toDateTimeAtStartOfDay.getMillis))
          .map(date => ZuoraAttributes(identityId, DigitalSubscriptionExpiryDate = Some(date)))
        maybeNonGifteeAttributes <- EitherT(AttributesMaker.zuoraAttributes(identityId, subscriptions, paymentMethodForPaymentMethodId, forDate).map(\/.right[String, Option[ZuoraAttributes]]))
        supporterProductDataAttributes <- futureSupporterProductDataAttributes
      } yield {
        val maybeGifteeAndNonGifteeAttributes = mergeDigitalSubscriptionExpiryDate(maybeNonGifteeAttributes, maybeGifteeAttributes)
        val maybeAttributesFromZuora = maybeGifteeAndNonGifteeAttributes.map(ZuoraAttributes.asAttributes(_))

        alertIfAttributesDoNotMatch(identityId, maybeAttributesFromZuora, supporterProductDataAttributes)

        attributesFromDynamo.foreach(maybeUpdateCache(maybeGifteeAndNonGifteeAttributes, _, maybeAttributesFromZuora))
        Future.successful("Zuora", maybeAttributesFromZuora)
      }

    lazy val fallbackToCache = (zuoraError: String) => {
      SafeLogger.error(scrub"Failed to retrieve attributes from Zuora for $identityId because $zuoraError so falling back on cache.")
      expensiveMetrics.countRequest("cache-fallback")
      attributesFromDynamo.map(maybeDynamoAttributes => ("Dynamo", maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_))))
    }

    implicit val scheduler: Scheduler = system.scheduler

    // try zuora first and fallback to cache
    EitherT(retry(getAttrFromZuora.run))                     // Zuora takes precedence
      .leftMap(fallbackToCache)                              // handles Zuora un-parsable response etc.
      .merge
      .flatten
      .recoverWith { case e => fallbackToCache(e.toString) } // handles Zuora timeouts etc.
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
    subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]]
  ): Future[Disjunction[String, List[AccountWithSubscriptions]]] = {

    def isNotDigipackGiftSub(subscription: Subscription[AnyPlan]) = subscription.asDigipack.isEmpty || subscription.readerType != Gift

    def accountWithSubscriptions(account: AccountObject)(implicit reads: SubPlanReads[AnyPlan]): Future[\/[String, AccountWithSubscriptions]] = {
      val nonDigipackGiftSubs = EitherT(subscriptionsForAccountId(account.Id)(anyPlanReads)).map(
          _.filter(isNotDigipackGiftSub) //Filter out digital pack gift subs where the current user is the purchaser rather than the recipient
      )
      nonDigipackGiftSubs
        .map(AccountWithSubscriptions(account, _))
        .run
    }

    getAccountsResponse
      .records
      .traverse(accountWithSubscriptions(_)(anyPlanReads))
      .map(_.sequenceU)
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
}

object AttributesFromZuora extends LazyLogging {
  def mergeDigitalSubscriptionExpiryDate(regular: Option[ZuoraAttributes], gift: Option[ZuoraAttributes]) = {
    val maybeExpiryDates = Some(
      List(regular, gift).flatten.flatMap(_.DigitalSubscriptionExpiryDate)
    ).filter(_.nonEmpty)

    val maybeLatestDate = maybeExpiryDates.map(
      _.maxBy(_.toDateTimeAtStartOfDay.getMillis)
    )

    regular.map(_.copy(DigitalSubscriptionExpiryDate = maybeLatestDate)).orElse(gift)
  }

  val metrics = Metrics("AttributesFromZuora") //referenced in CloudFormation

  def alertIfAttributesDoNotMatch(identityId: String, fromZuora: Option[Attributes], fromSupporterProductData: Option[Attributes]): Unit =
    if (!contentAccessMatches(fromZuora, fromSupporterProductData)) {
      logger.warn(
        s"Mismatched attributes for user $identityId\n" +
        s"Zuora returned: ${Json.toJson(fromZuora)}\n" +
        s"supporter-product-data returned: ${Json.toJson(fromSupporterProductData)}"
      )
      metrics.put("AttributesMismatch", 1) //referenced in CloudFormation
    } else logger.info("attributes returned from Zuora match those returned from supporter-product-data")

  def contentAccessMatches(fromZuora: Option[Attributes], fromSupporterProductData: Option[Attributes]) =
    fromZuora.map(_.contentAccess.copy(recurringContributor = false)) == fromSupporterProductData.map(_.contentAccess.copy(recurringContributor = false)) ||
      isCancelledRecurringContribution(fromZuora, fromSupporterProductData) ||
      isExpiredSub(fromZuora, fromSupporterProductData)

  def isExpiredSub(fromZuora: Option[Attributes], fromSupporterProductData: Option[Attributes]) =
    fromSupporterProductData.isEmpty && fromZuora.exists(att =>
      att.contentAccess == ContentAccess(
        member = false,
        paidMember = false,
        recurringContributor = false,
        digitalPack = false,
        paperSubscriber = false,
        guardianWeeklySubscriber = false
      )
    )

  def isCancelledRecurringContribution(fromZuora: Option[Attributes], fromSupporterProductData: Option[Attributes]) =
    fromZuora.isEmpty && fromSupporterProductData.exists(att =>
      att.contentAccess == ContentAccess(
        member = false,
        paidMember = false,
        recurringContributor = true,
        digitalPack = false,
        paperSubscriber = false,
        guardianWeeklySubscriber = false
      )
    )

}

