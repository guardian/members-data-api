package services.subscription

import com.gu.memsub
import com.gu.memsub.Subscription.Name
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.monitoring.SafeLogger.LogPrefix
import models.ApiError
import org.joda.time.LocalDate
import scalaz.{EitherT, Monad}
import services.zuora.rest.ZuoraRestService
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

class CancelSubscription(subscriptionService: SubscriptionService[Future], zuoraRestService: ZuoraRestService)(implicit m: Monad[Future]) {
  def cancel(
      subscriptionName: Name,
      cancellationEffectiveDate: Option[LocalDate],
      reason: String,
      accountId: memsub.Subscription.AccountId,
      endOfTermDate: LocalDate,
  )(implicit ec: ExecutionContext, logPrefix: LogPrefix): EitherT[ApiError, Future, Option[LocalDate]] =
    (for {
      _ <- disableAutoPayOnlyIfAccountHasOneSubscription(accountId).leftMap(message => s"Failed to disable AutoPay: $message")
      _ <- EitherT(zuoraRestService.updateCancellationReason(subscriptionName, reason)).leftMap(message =>
        s"Failed to update cancellation reason: $message",
      )
      _ <- EitherT(zuoraRestService.cancelSubscription(subscriptionName, endOfTermDate, cancellationEffectiveDate)).leftMap(message =>
        s"Failed to execute Zuora cancellation proper: $message",
      )
    } yield cancellationEffectiveDate).leftMap(ApiError(_, "", 500))

  /** If user has multiple subscriptions within the same billing account, then disabling auto-pay on the account would stop collecting payments for
    * all subscriptions including the non-cancelled ones. In this case debt would start to accumulate in the form of positive Zuora account balance,
    * and if at any point auto-pay is switched back on, then payment for the entire amount would be attempted.
    */
  def disableAutoPayOnlyIfAccountHasOneSubscription(
      accountId: memsub.Subscription.AccountId,
  )(implicit ec: ExecutionContext, logPrefix: LogPrefix): SimpleEitherT[Unit] = {
    EitherT(subscriptionService.subscriptionsForAccountId(accountId)).flatMap { currentSubscriptions =>
      if (currentSubscriptions.size <= 1)
        SimpleEitherT(zuoraRestService.disableAutoPay(accountId).map(_.toEither))
      else // do not disable auto pay
        SimpleEitherT.right({})
    }
  }
}
