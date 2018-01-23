package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.ZuoraRestService.QueryResponse
import loghandling.LoggingField.LogField
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models.Attributes
import org.joda.time.{DateTime, LocalDate}
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
                    dynamoAttributeService: AttributeService,
                    forDate: LocalDate = LocalDate.now(),
                    projectedExpiry: => DateTime = Timestamper.projectedExpiryDate): Future[(String, Option[Attributes])] = {

    val attributesDisjunction: DisjunctionT[Future, String, Option[Attributes]] = for {
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
      AttributesMaker.attributes(identityId, subscriptions, forDate)
    }


    val attributesFromDynamo: Future[(String, Option[Attributes])] = dynamoAttributeService.get(identityId) map { attributes => ("Dynamo", attributes)}

    val attributesFromZuora: Future[Disjunction[String, Option[Attributes]]] = attributesDisjunction.run.map {
        _.leftMap { errorMsg =>
          log.error(s"Tried to get Attributes for $identityId but failed with $errorMsg")
          errorMsg
      }
    }

    val attributesFromZuoraOrDynamoFallback: Future[(String, Option[Attributes])] = for {
      dynamoAttributes <- attributesFromDynamo
      zuoraAttributes <- attributesFromZuora
    } yield zuoraAttributes.fold(_ => dynamoAttributes, ("Zuora", _))

    val zuoraOrCachedAttributes = attributesFromZuoraOrDynamoFallback fallbackTo attributesFromDynamo

    zuoraOrCachedAttributes flatMap { attributesFromSomewhere =>
      val (fromWhere: String, attributes: Option[Attributes]) = attributesFromSomewhere

      if(fromWhere == "Zuora") {
        attributesFromDynamo map { case (_, dynamoAttributes) =>
          val zuoraAttributesWithAdfree: Option[Attributes] = attributesWithAdFreeFlagFromDynamo(attributes, dynamoAttributes)

          val updateRequired = dynamoUpdateRequired(dynamoAttributes, attributes, identityId)
          if(updateRequired) {
            updateCache(identityId, zuoraAttributesWithAdfree, dynamoAttributeService).onFailure {
              case e: Throwable =>
                log.error(s"Future failed when attempting to update cache.", e)
                log.warn(s"Future failed when attempting to update cache. Attributes from Zuora: $attributes")
            }
          }

          (fromWhere, zuoraAttributesWithAdfree)
        }
      }
      else Future.successful(fromWhere, attributes)
    }
  }



  def dynamoUpdateRequired(dynamoAttributes: Option[Attributes], zuoraAttributes: Option[Attributes], identityId: String, projectedExpiry: => DateTime = Timestamper.projectedExpiryDate) = {

    def ttlUpdateRequired(currentExpiry: DateTime) = projectedExpiry.isAfter(currentExpiry.plusDays(1))

    def calculateExpiry(currentExpiry: Option[DateTime]): DateTime = currentExpiry.map { expiry =>
      log.info(s"Calculating expiry for user ${identityId} with current expiry $expiry. Projected expiry is $projectedExpiry.")
      if (ttlUpdateRequired(expiry)) {
        log.info(s"TTL update required for user $identityId")
        projectedExpiry
      }  else {
        log.info(s"No TTL update required for user $identityId")
        expiry
      }
    }.getOrElse {
      log.info(s"Record for user $identityId has no TTL so setting TTL to $projectedExpiry.")
      projectedExpiry
    }

    val currentExpiry: Option[DateTime] = dynamoAttributes.flatMap { attributes => attributes.TTLTimestamp.map { timestamp => Timestamper.toDateTime(timestamp) } }
    val newExpiry: DateTime = calculateExpiry(currentExpiry)

    def expiryShouldChange(dynamoAttributes: Option[Attributes], currentExpiry: Option[DateTime], newExpiry: DateTime) = dynamoAttributes.isDefined && !currentExpiry.contains(newExpiry)

    expiryShouldChange(dynamoAttributes, currentExpiry, newExpiry) || !dynamoAndZuoraAgree(dynamoAttributes, zuoraAttributes, identityId)

  }



  private def updateCache(identityId: String, zuoraAttributesWithAdfree: Option[Attributes], dynamoAttributeService: AttributeService): Future[Unit] = {
    zuoraAttributesWithAdfree match {
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

  def attributesWithAdFreeFlagFromDynamo(attributesFromZuora: Option[Attributes], attributesFromDynamo: Option[Attributes]) = {
    val adFreeFlagFromDynamo = attributesFromDynamo flatMap (_.AdFree)

    attributesFromZuora map { zuoraAttributes =>
      zuoraAttributes.copy(AdFree = adFreeFlagFromDynamo)
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

  def dynamoAndZuoraAgree(maybeDynamoAttributes: Option[Attributes], maybeZuoraAttributes: Option[Attributes], identityId: String): Boolean = { //todo get identityid from attributes
    val zuoraAttributesWithIgnoredFields = maybeZuoraAttributes map { zuoraAttributes =>
      maybeDynamoAttributes match {
        case Some(dynamoAttributes) => zuoraAttributes.copy(
          AdFree = dynamoAttributes.AdFree, //fetched from Dynamo in the Zuora lookup anyway (dynamo is the source of truth)
          Wallet = dynamoAttributes.Wallet, //can't be found based on Zuora lookups, and not currently used
          MembershipNumber = dynamoAttributes.MembershipNumber, //I don't think membership number is needed and it comes from Salesforce
          MembershipJoinDate = dynamoAttributes.MembershipJoinDate.flatMap(_ => zuoraAttributes.MembershipJoinDate), //only compare if dynamo has value
          DigitalSubscriptionExpiryDate = None,
          TTLTimestamp = dynamoAttributes.TTLTimestamp //TTL only in dynamo
        )
        case None => zuoraAttributes
      }

     }
     val dynamoAndZuoraAgree = zuoraAttributesWithIgnoredFields == maybeDynamoAttributes
     if (!dynamoAndZuoraAgree)
       log.info(s"We looked up attributes via Zuora for $identityId and Zuora and Dynamo disagreed." +
         s" Zuora attributes: $maybeZuoraAttributes, parsed as: $zuoraAttributesWithIgnoredFields. Dynamo attributes: $maybeDynamoAttributes.")

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

