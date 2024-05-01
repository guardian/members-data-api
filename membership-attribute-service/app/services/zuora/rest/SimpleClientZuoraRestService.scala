package services.zuora.rest

import com.gu.memsub.Subscription._
import com.gu.monitoring.SafeLogging
import com.gu.zuora.rest.ZuoraResponse
import org.joda.time.LocalDate
import scalaz.{Name => avoidclash, _}
import services.zuora.rest.ZuoraRestService._

import scala.concurrent.{ExecutionContext, Future}

class SimpleClientZuoraRestService(private val simpleRest: SimpleClient)(implicit val m: Monad[Future], ec: ExecutionContext)
    extends ZuoraRestService
    with SafeLogging {

  def getAccount(accountId: AccountId): Future[String \/ AccountSummary] = {
    simpleRest.get[AccountSummary](s"accounts/${accountId.get}/summary") // TODO error handling
  }

  def getObjectAccount(accountId: AccountId): Future[String \/ ObjectAccount] = {
    simpleRest.get[ObjectAccount](s"object/account/${accountId.get}")
  }

  def getGiftSubscriptionRecordsFromIdentityId(identityId: String): Future[String \/ List[GiftSubscriptionsFromIdentityIdRecord]] = {
    val today = LocalDate.now().toString("yyyy-MM-dd")
    val queryString =
      s"select name, id, termEndDate from subscription where GifteeIdentityId__c = '${identityId}' and status = 'Active' and termEndDate >= '$today'"
    val response = simpleRest.post[RestQuery, GiftSubscriptionsFromIdentityIdResponse]("action/query", RestQuery(queryString))
    EitherT(response).map(_.records).run
  }

  def getPaymentMethod(paymentMethodId: String): Future[String \/ PaymentMethodResponse] =
    simpleRest.get[PaymentMethodResponse](s"object/payment-method/$paymentMethodId")

  private def unsuccessfulResponseToLeft(restResponse: EitherT[String, Future, ZuoraResponse]): EitherT[String, Future, ZuoraResponse] = {
    val futureMonad = implicitly[Monad[Future]]

    val validated = futureMonad.map(restResponse.run) {
      case \/-(zuoraResponse) =>
        if (zuoraResponse.success) \/.r[String](zuoraResponse)
        else \/.l[ZuoraResponse](zuoraResponse.error.getOrElse("Zuora returned with success = false"))
      case -\/(e) => \/.l[ZuoraResponse](e)
    }

    EitherT(validated)
  }

  def cancelSubscription(
      subscriptionName: Name,
      termEndDate: LocalDate,
      maybeChargedThroughDate: Option[
        LocalDate,
      ], // FIXME: Optionality should probably be removed and semantics changed to cancellationEffectiveDate (see comments bellow)
  )(implicit ex: ExecutionContext): Future[String \/ Unit] = {

    // FIXME: Not always safe assumption. There are multiple scenarios to consider
    //   1. Free trial should be explicitly handled: val cancellationEffectiveDate = if(sub.startDate <= today && sub.acceptanceDate > today) LocalDate.now
    //   2. If outside trial, and invoiced, ChargedThroughDate should always exist: val cancellationEffectiveDate = ChargedThroughDate
    //   3. If outside trial, and invoiced, but ChargedThroughDate does not exist, then it is a likely logic error. Investigate ASAP!. Currently it happens after Contributions amount change.
    val cancellationEffectiveDate =
      maybeChargedThroughDate.getOrElse(LocalDate.now) // immediate cancellation for subs which aren't yet invoiced (e.g. during digipack trial)

    val extendTermIfNeeded = maybeChargedThroughDate
      .filter(_.isAfter(termEndDate)) // we need to extend the term if they've paid past their term end date, otherwise cancel call will fail
      .map(_ =>
        EitherT(simpleRest.put[RenewSubscriptionCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}/renew", RenewSubscriptionCommand())),
      )
      .getOrElse(EitherT.right[String, Future, ZuoraResponse](ZuoraResponse(success = true)))

    val cancelCommand = CancelSubscriptionCommand(cancellationEffectiveDate)

    val restResponse = for {
      _ <- extendTermIfNeeded
      cancelResponse <- EitherT(
        simpleRest.put[CancelSubscriptionCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}/cancel", cancelCommand),
      )
    } yield cancelResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def updateCancellationReason(subscriptionName: Name, userCancellationReason: String): Future[String \/ Unit] = {
    val future = implicitly[Monad[Future]]
    val restResponse = for {
      restResponse <- EitherT(
        simpleRest.put[UpdateCancellationSubscriptionCommand, ZuoraResponse](
          s"subscriptions/${subscriptionName.get}",
          UpdateCancellationSubscriptionCommand(cancellationReason = "Customer", userCancellationReason = userCancellationReason),
        ),
      )
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def disableAutoPay(accountId: AccountId): Future[String \/ Unit] = {
    val future = implicitly[Monad[Future]]

    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[DisableAutoPayCommand, ZuoraResponse](s"accounts/${accountId.get}", DisableAutoPayCommand()))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def updateChargeAmount(
      subscriptionName: Name,
      ratePlanChargeId: SubscriptionRatePlanChargeId,
      ratePlanId: RatePlanId,
      amount: Double,
      reason: String,
      applyFromDate: LocalDate,
  )(implicit ex: ExecutionContext): Future[\/[String, Unit]] = {
    val updateCommand =
      UpdateChargeCommand(price = amount, ratePlanChargeId = ratePlanChargeId, ratePlanId = ratePlanId, applyFromDate = applyFromDate, note = reason)
    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[UpdateChargeCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}", updateCommand))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def getCancellationEffectiveDate(name: Name): Future[String \/ Option[String]] = {
    (for {
      amendment <- EitherT(simpleRest.get[Amendment](s"amendments/subscriptions/${name.get}"))
      cancelledSub <- EitherT(simpleRest.get[CancelledSubscription](s"subscriptions/${name.get}"))
    } yield {
      if (amendment.`type`.contains("Cancellation") && cancelledSub.status == "Cancelled")
        Some(cancelledSub.subscriptionEndDate)
      else
        None
    }).run
  }

}
