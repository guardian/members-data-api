package services.zuora.payment

import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.{CardUpdateFailure, CardUpdateSuccess, PaymentCard, PaymentCardDetails, PaymentCardUpdateResult}
import com.gu.stripe.Stripe
import scalaz.Monad
import services.stripe.StripeService
import services.zuora.soap.ZuoraSoapService
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

class SetPaymentCard(paymentService: ZuoraPaymentService, zuoraService: ZuoraSoapService, stripeServiceEither: Either[String, StripeService])(implicit
    ec: ExecutionContext,
    m: Monad[Future],
) {
  def apply(useStripePaymentMethod: Boolean, accountId: AccountId, stripeCardIdentifier: String): SimpleEitherT[PaymentCardUpdateResult] = {
    for {
      stripeService <- SimpleEitherT.fromEither(stripeServiceEither)
      result <- SimpleEitherT.rightT(setPaymentCard(useStripePaymentMethod, stripeService)(accountId, stripeCardIdentifier))
    } yield result
  }

  private def setPaymentCard(
      useStripePaymentMethod: Boolean,
      stripeService: StripeService,
  )(accountId: AccountId, stripeCardIdentifier: String): Future[PaymentCardUpdateResult] = {
    val createCustomerFunction =
      if (useStripePaymentMethod)
        stripeService.createCustomerWithStripePaymentMethod(_)
      else
        stripeService.createCustomer(_)

    (for {
      customer <- createCustomerFunction(stripeCardIdentifier)
      _ <- zuoraService
        .createCreditCardPaymentMethod(accountId, customer, stripeService.paymentIntentsGateway, stripeService.invoiceTemplateOverride)
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
