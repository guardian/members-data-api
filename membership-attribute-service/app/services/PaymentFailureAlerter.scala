package services

import java.util.Locale

import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.zuora.api.{RegionalStripeGateways, StripeAUMembershipGateway, StripeUKMembershipGateway}
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, AccountSummary, PaymentMethodId, PaymentMethodResponse}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.concurrent.{ExecutionContext, Future}
import scalaz.syntax.std.boolean._
import scalaz.{Disjunction, \/}


object PaymentFailureAlerter {

  private def accountObject(accountSummary: AccountSummary) =
    AccountObject(
      Id = accountSummary.id,
      Balance = accountSummary.balance,
      Currency = accountSummary.currency,
      DefaultPaymentMethodId = accountSummary.defaultPaymentMethod.map(_.id),
      PaymentGateway = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry)
    )


  def membershipAlertText(accountSummary: AccountSummary, subscription: Subscription[AnyPlan],
                          paymentMethodGetter: PaymentMethodId => Future[String \/ PaymentMethodResponse])(implicit ec: ExecutionContext) : Future[Option[String]] = {

    def expectedAlertText: Future[Option[String]] = {
      val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)
      val paymentMethodLatestDateFormatted: Future[String \/ String] = accountSummary.defaultPaymentMethod.map(_.id) match {
        case Some(id) => {
          val paymentMethod: Future[Disjunction[String, PaymentMethodResponse]] = paymentMethodGetter(id) fallbackTo Future.successful(\/.left("Failed to get payment method"))
          paymentMethod map { paymentMethodDisjunction: Disjunction[String, PaymentMethodResponse] => paymentMethodDisjunction map { pm => pm.lastTransactionDateTime.toString(formatter)}}
        }
        case None => Future.successful(\/.left("No payment method id and so no payment method!"))
      }

      paymentMethodLatestDateFormatted.map { formattedDateDisjunction: Disjunction[String, String] =>
        val alertTextWithDate = formattedDateDisjunction map { date: String =>
          val productName = subscription.plan.name
          s"Our attempt to take payment for your $productName membership failed on $date. Please check below that your card details are up to date."
        }
        alertTextWithDate.toOption
      }
    }

    alertAvailableFor(accountObject(accountSummary), subscription, paymentMethodGetter) flatMap  { shouldShowAlert: Boolean =>
      expectedAlertText.map { someText => shouldShowAlert.option (someText).flatten }
    }

  }

  def alertAvailableFor(
    account: AccountObject, subscription: Subscription[AnyPlan],
    paymentMethodGetter: PaymentMethodId => Future[String \/ PaymentMethodResponse]
  )(implicit ec: ExecutionContext): Future[Boolean] = {

    def creditCard(paymentMethodResponse: PaymentMethodResponse) = paymentMethodResponse.paymentMethodType == "CreditCardReferenceTransaction" || paymentMethodResponse.paymentMethodType == "CreditCard"

    val stillFreshInDays = 27
    def recentEnough(lastTransationDateTime: DateTime) = lastTransationDateTime.plusDays(stillFreshInDays).isAfterNow
    val isActionablePaymentGateway = account.PaymentGateway.exists(gw => gw == StripeUKMembershipGateway || gw == StripeAUMembershipGateway)

    val userInPaymentFailure: Future[\/[String, Boolean]] = {
      if(account.Balance > 0 && account.DefaultPaymentMethodId.isDefined && isActionablePaymentGateway) {
        val eventualPaymentMethod: Future[\/[String, PaymentMethodResponse]] = paymentMethodGetter(account.DefaultPaymentMethodId.get)

        eventualPaymentMethod map { maybePaymentMethod: \/[String, PaymentMethodResponse] =>
          maybePaymentMethod.map { pm: PaymentMethodResponse =>
            creditCard(pm) &&
              pm.numConsecutiveFailures > 0 &&
              recentEnough(pm.lastTransactionDateTime)
          }
        }
      }
      else Future.successful(\/.right(false))
    }

    userInPaymentFailure map (_.getOrElse(false))
  }
}
