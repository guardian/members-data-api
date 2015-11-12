package controllers

import actions._
import com.gu.membership.salesforce._
import com.gu.membership.stripe.StripeService
import com.gu.membership.zuora.SubscriptionService
import com.gu.membership.zuora.soap
import com.gu.membership.zuora.rest
import com.gu.services.PaymentService
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models.{Attributes, Features}
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import play.libs.Akka
import services.{AuthenticationService, IdentityAuthService}
import models.AccountDetails._

import scala.concurrent.Future

class AttributeController {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val backendAction = BackendFromCookieAction
  lazy val metrics = CloudWatch("AttributesController")

  private def lookup(endpointDescription: String, onSuccess: Attributes => Result, onNotFound: Result = notFound) =
    backendAction.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        request.attributeService.get(id).map {
          case Some(attrs) =>
            metrics.put(s"$endpointDescription-lookup-successful", 1)
            onSuccess(attrs)
          case None =>
            metrics.put(s"$endpointDescription-user-not-found", 1)
            onNotFound
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }

  def membership = lookup("membership", identity[Attributes])

  def features = lookup("features",
    onSuccess = Features.fromAttributes,
    onNotFound = Features.unauthenticated
  )

  def membershipDetails = paymentDetails("Membership")
  def digitalPackDetails = paymentDetails("Digital Pack")

  def paymentDetails(service: String) = TouchpointFromCookieAction.async { implicit request =>
    authenticationService.userId.fold[Future[Result]](Future(cookiesRequired))({userId =>
      val soapClient = new soap.Client(request.config.zuoraSoap, request.metrics("zuora-soap"), Akka.system)
      val restClient = new rest.Client(request.config.zuoraRest, request.metrics("zuora-rest"))

      val subService = new SubscriptionService(soapClient, restClient)
      val stripeService: StripeService = new StripeService(request.config.stripe, request.metrics("stripe"))
      val ps = new PaymentService(stripeService, subService)

      val contacts = new SimpleContactRepository(request.config.salesforce,Akka.system().scheduler, "API")

      (for {
        contact <- contacts.get(userId) map { m => m.getOrElse(throw new IllegalStateException())}
        paymentDetails <- ps.paymentDetails(contact, service)
      } yield (contact, paymentDetails).toResult).recover {case e: IllegalStateException => notFound}
      })
  }
}
