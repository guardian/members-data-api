package services

import java.util.Locale

import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.zuora.api.RegionalStripeGateways
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, AccountSummary, PaymentMethodId, PaymentMethodResponse}
import org.joda.time.format.DateTimeFormat

import scala.concurrent.{ExecutionContext, Future}
import scalaz.syntax.std.boolean._
import scalaz.{Disjunction, \/}


object PaymentFailureAlerter {

  def accountObject(accountSummary: AccountSummary) =
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
      val formatter = DateTimeFormat.forPattern("dd MMMM yyyy").withLocale(Locale.ENGLISH)
      val paymentMethodLatestDateFormatted: Future[String \/ String] = accountSummary.defaultPaymentMethod.map(_.id) match {
        case Some(id) => {
          val paymentMethod: Future[Disjunction[String, PaymentMethodResponse]] = paymentMethodGetter(id) fallbackTo Future.successful(\/.left("Failed to get payment method")) //todo do I want to do this??
          paymentMethod map { paymentMethodDisjunction: Disjunction[String, PaymentMethodResponse] => paymentMethodDisjunction map { pm => pm.lastTransactionDateTime.toString(formatter)}}
        }
        case None => Future.successful(\/.left("No payment method id and so no payment method!"))
      }

      paymentMethodLatestDateFormatted.map { formattedDateDisjunction: Disjunction[String, String] =>
        val alertTextWithDate = formattedDateDisjunction map { date: String =>
          s"Our attempt to take payment for your membership failed on ${date}. Please check below that your card details are up to date."
        }
        alertTextWithDate.toOption
      }
    }

    AttributesMaker.alertAvailableFor(accountObject(accountSummary), subscription, paymentMethodGetter) flatMap  { shouldShowAlert: Boolean =>
      expectedAlertText.map { someText => shouldShowAlert.option (someText).flatten }
    }

  }
}
