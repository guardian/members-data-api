package services.zuora.rest

import com.gu.memsub.Subscription._
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.zuora.rest.{SimpleClient, ZuoraResponse}
import org.joda.time.LocalDate
import play.api.libs.json.{Json, Reads}
import scalaz.{Name => avoidclash, _}
import services.zuora.rest.ZuoraRestService._

import scala.concurrent.{ExecutionContext, Future}

object SimpleClientZuoraRestService {
  case class OrderResponse(success: Boolean, status: Option[String])

  object OrderResponse {
    def completed(response: OrderResponse): Either[String, Unit] = response match {
      case OrderResponse(true, Some("Completed")) => Right(())
      case OrderResponse(success, status) =>
        Left(s"Zuora order completed with success = $success and status = ${status.getOrElse("missing")}")
    }
  }

  implicit val orderResponseReads: Reads[OrderResponse] = Json.reads[OrderResponse]
}

class SimpleClientZuoraRestService(
    private val simpleRest: SimpleClient[Future],
    private val currentDate: () => LocalDate = () => LocalDate.now,
)(implicit val m: Monad[Future])
    extends ZuoraRestService
    with SafeLogging {

  import SimpleClientZuoraRestService._

  def getAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[String \/ AccountSummary] = {
    simpleRest.get[AccountSummary](s"accounts/${accountId.get}/summary") // TODO error handling
  }

  def getObjectAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[String \/ ObjectAccount] = {
    simpleRest.get[ObjectAccount](s"object/account/${accountId.get}")
  }

  def getGiftSubscriptionRecordsFromIdentityId(
      identityId: String,
  )(implicit logPrefix: LogPrefix): Future[String \/ List[GiftSubscriptionsFromIdentityIdRecord]] = {
    val today = LocalDate.now().toString("yyyy-MM-dd")
    val queryString =
      s"select name, id, termEndDate from subscription where GifteeIdentityId__c = '${identityId}' and status = 'Active' and termEndDate >= '$today'"
    val response = simpleRest.post[RestQuery, GiftSubscriptionsFromIdentityIdResponse]("action/query", RestQuery(queryString))
    EitherT(response).map(_.records).run
  }

  def getPaymentMethod(paymentMethodId: String)(implicit logPrefix: LogPrefix): Future[String \/ PaymentMethodResponse] =
    simpleRest.get[PaymentMethodResponse](s"object/payment-method/$paymentMethodId")

  def getBillingPreview(accountId: AccountId, targetDate: LocalDate)(implicit
      logPrefix: LogPrefix,
  ): Future[String \/ List[BillingPreviewInvoiceItem]] = {
    val request = BillingPreviewRequest(accountId.get, targetDate)
    EitherT(simpleRest.post[BillingPreviewRequest, BillingPreviewResponse]("operations/billing-preview", request))
      .map(_.invoiceItems)
      .run
  }

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

  private def validateCompletedOrder(restResponse: EitherT[String, Future, OrderResponse]): EitherT[String, Future, Unit] =
    for {
      response <- restResponse
      _ <- EitherT.fromEither(Future.successful(OrderResponse.completed(response)))
    } yield ()

  def cancelSubscription(
      subscriptionNumber: SubscriptionNumber,
      accountId: AccountId,
      termEndDate: LocalDate,
      maybeChargedThroughDate: Option[
        LocalDate,
      ], // FIXME: Optionality should probably be removed and semantics changed to cancellationEffectiveDate (see comments bellow)
  )(implicit ex: ExecutionContext, logPrefix: LogPrefix): Future[String \/ Unit] = {

    // FIXME: Not always safe assumption. There are multiple scenarios to consider
    //   1. Free trial should be explicitly handled: val cancellationEffectiveDate = if(sub.startDate <= today && sub.acceptanceDate > today) LocalDate.now
    //   2. If outside trial, and invoiced, ChargedThroughDate should always exist: val cancellationEffectiveDate = ChargedThroughDate
    //   3. If outside trial, and invoiced, but ChargedThroughDate does not exist, then it is a likely logic error. Investigate ASAP!. Currently it happens after Contributions amount change.
    val orderDate = currentDate()
    val cancellationEffectiveDate = maybeChargedThroughDate.getOrElse(orderDate)

    /** Zuora rejects a cancellation after the current term end date, even when the subscriber has already paid beyond it. */
    val needsTermRenewal = maybeChargedThroughDate.exists(_.isAfter(termEndDate))
    val order = CancellationOrderRequest.forSubscription(
      accountId,
      subscriptionNumber,
      orderDate,
      cancellationEffectiveDate,
      needsTermRenewal,
    )

    validateCompletedOrder(EitherT(simpleRest.post[CancellationOrderRequest, OrderResponse]("orders", order))).run
  }

  def updateCancellationReason(subscriptionNumber: SubscriptionNumber, userCancellationReason: String)(implicit
      logPrefix: LogPrefix,
  ): Future[String \/ Unit] = {
    val future = implicitly[Monad[Future]]
    val restResponse = for {
      restResponse <- EitherT(
        simpleRest.put[UpdateCancellationSubscriptionCommand, ZuoraResponse](
          s"subscriptions/${subscriptionNumber.getNumber}",
          UpdateCancellationSubscriptionCommand(cancellationReason = "Customer", userCancellationReason = userCancellationReason),
        ),
      )
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def disableAutoPay(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[String \/ Unit] = {
    val future = implicitly[Monad[Future]]

    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[DisableAutoPayCommand, ZuoraResponse](s"accounts/${accountId.get}", DisableAutoPayCommand()))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def updateChargeAmount(
      subscriptionNumber: SubscriptionNumber,
      ratePlanChargeId: SubscriptionRatePlanChargeId,
      ratePlanId: RatePlanId,
      amount: Double,
      reason: String,
      applyFromDate: LocalDate,
  )(implicit ex: ExecutionContext, logPrefix: LogPrefix): Future[\/[String, Unit]] = {
    val updateCommand =
      UpdateChargeCommand(price = amount, ratePlanChargeId = ratePlanChargeId, ratePlanId = ratePlanId, applyFromDate = applyFromDate, note = reason)
    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[UpdateChargeCommand, ZuoraResponse](s"subscriptions/${subscriptionNumber.getNumber}", updateCommand))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def getCancellationEffectiveDate(subscriptionNumber: SubscriptionNumber)(implicit logPrefix: LogPrefix): Future[String \/ Option[String]] = {
    (for {
      cancelledSub <- EitherT(simpleRest.get[CancelledSubscription](s"subscriptions/${subscriptionNumber.getNumber}"))
    } yield {
      if (cancelledSub.status == "Cancelled")
        Some(cancelledSub.subscriptionEndDate)
      else
        None
    }).run
  }

}
