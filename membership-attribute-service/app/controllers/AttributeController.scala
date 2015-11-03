package controllers

import com.gu.membership.salesforce.ContactDeserializer.Keys
import com.gu.monitoring.ServiceMetrics
import configuration.Config
import actions.BackendFromCookieAction
import com.gu.membership.salesforce._
import com.gu.membership.salesforce.Contact._
import com.gu.membership.stripe.StripeService
import com.gu.membership.touchpoint.TouchpointBackendConfig
import com.gu.membership.zuora.SubscriptionService
import com.gu.membership.zuora.soap
import com.gu.membership.zuora.rest
import com.gu.services.{PaymentService, PaymentDetails}
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models.{Attributes, Features}
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{Controller, Result}
import play.libs.Akka
import services.{AuthenticationService, IdentityAuthService}

import scala.concurrent.Future

class AttributeController extends Controller {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val backendAction = BackendFromCookieAction
  lazy val metrics = CloudWatch("AttributesCoentroller")

  implicit class FutureLike[T](arg: Option[T]) {
    def future: Future[T] = Future { arg.getOrElse(throw new IllegalStateException()) }
  }
  
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

  def paymentDetails(service: String) = backendAction.async { implicit request =>

    authenticationService.userId.map({userId =>

      val config = Config.config.getConfig("touchpoint.backend")
      val tpConfig: TouchpointBackendConfig = TouchpointBackendConfig.byEnv("UAT", config)
      val tempServiceMetrics = new ServiceMetrics(Config.stage, "members-data-api",_: String)

      val soapClient = new soap.Client(tpConfig.zuoraSoap, tempServiceMetrics("zuora-soap"), Akka.system)
      val restClient = new rest.Client(tpConfig.zuoraRest, tempServiceMetrics("zuora-rest"))

      val subService = new SubscriptionService(soapClient, restClient)
      val stripeService: StripeService = new StripeService(tpConfig.stripe, tempServiceMetrics("stripe"))
      val ps = new PaymentService(stripeService, subService)

      val simpleSalesForce = new SimpleContactRepository(tpConfig.salesforce,Akka.system().scheduler, "API")

      (for {
        member <- simpleSalesForce.get(userId) flatMap {a => a.future}
        paymentDetails <- ps.paymentDetails(member, service)
      } yield Ok(basicDetails(member)++toJson(paymentDetails)))
        .recover {
          case e: IllegalStateException => NotFound
        }

    }).getOrElse(Future{Forbidden})
  }

  def basicDetails(member: Contact[MemberStatus, PaymentMethod]): JsObject = Json.obj(
    "userId" -> member.identityId,
    "firstName" -> member.firstName,
    "joinDate" -> member.joinDate,
    "benefits" -> Json.obj(
      "discountedEventTickets" -> false, //todo!
      "complimentaryEventTickets" -> false //todo also!
    )
  )

  def memberDetails(contact: Contact[MemberStatus, PaymentMethod]): JsObject = contact.memberStatus match {
    case m: Member => Json.obj("regNumber" -> m.regNumber.mkString, "tier" -> m.tier.name, "isPaidTier" -> m.tier.isPaid)
    case _ => Json.obj()
  }

  private def toJson(paymentDetails: PaymentDetails): JsObject = {

    val card = paymentDetails.plan.card.fold(Json.obj())(card => Json.obj(
      "card" -> Json.obj(
        "last4" -> card.last4,
        "type" -> card.`type`
      )
    ))

    Json.obj(
      "optIn" -> !paymentDetails.pendingCancellation,
      "subscription" -> (card ++ Json.obj(
        "start" -> paymentDetails.startDate,
        "end" -> paymentDetails.termEndDate,
        "nextPaymentPrice" -> paymentDetails.nextPaymentPrice,
        "nextPaymentDate" -> paymentDetails.nextPaymentDate,
        "renewalDate" -> paymentDetails.termEndDate,
        "cancelledAt" -> paymentDetails.pendingAmendment,
        "plan" -> Json.obj(
          "name" -> paymentDetails.plan.name,
          "amount" -> paymentDetails.plan.amount,
          "interval" -> (if (paymentDetails.plan.interval.getOrElse("") == "Annual") "year" else "month")
        )))
    )
  }
}
