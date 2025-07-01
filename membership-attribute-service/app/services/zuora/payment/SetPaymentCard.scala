package services.zuora.payment

import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.{CardUpdateFailure, CardUpdateSuccess, PaymentCard, PaymentCardDetails, PaymentCardUpdateResult}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.stripe.Stripe
import com.gu.zuora.ZuoraSoapService
import scalaz.Monad
import services.stripe.StripeService
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

class SetPaymentCard(zuoraService: ZuoraSoapService, stripeServiceEither: Either[String, StripeService])(implicit
    ec: ExecutionContext,
    m: Monad[Future],
) {
  def setPaymentCard(useStripePaymentMethod: Boolean, accountId: AccountId, stripeCardIdentifier: String)(implicit
      logPrefix: LogPrefix,
  ): SimpleEitherT[PaymentCardUpdateResult] = {
    for {
      stripeService <- SimpleEitherT.fromEither(stripeServiceEither)
      result <- SimpleEitherT.rightT(setPaymentCard(useStripePaymentMethod, stripeService)(accountId, stripeCardIdentifier))
    } yield result
  }

  private def setPaymentCard(
      useStripePaymentMethod: Boolean,
      stripeService: StripeService,
  )(accountId: AccountId, stripeCardIdentifier: String)(implicit logPrefix: LogPrefix): Future[PaymentCardUpdateResult] = {
    val createCustomerFunction =
      if (useStripePaymentMethod)
        stripeService.createCustomerWithStripePaymentMethod(_)
      else
        stripeService.createCustomer(_)

    (for {
      customer <- createCustomerFunction(stripeCardIdentifier)
      _ <- zuoraService
        .createCreditCardPaymentMethod(accountId, customer, stripeService.paymentIntentsGateway)
    } yield {
      CardUpdateSuccess(
        PaymentCard(
          isReferenceTransaction = true,
          cardType = Some(customer.card.`type`),
          paymentCardDetails = Some(PaymentCardDetails(customer.card.last4, customer.card.exp_month, customer.card.exp_year)),
        ),
      )
    }).recover { case error: Stripe.Error =>
      CardUpdateFailure(error.`type`, error.message.getOrElse(""), error.code.getOrElse("unknown"))
    }
  }
}
