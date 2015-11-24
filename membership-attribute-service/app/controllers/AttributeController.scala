package controllers

import com.gu.config.ProductFamily
import com.gu.config.{DigitalPack, Membership, ProductFamily}
import com.gu.membership.salesforce.ContactRepository
import com.gu.services.PaymentService
import play.api.mvc.Action
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models.{Attributes, Features}
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import services.{AttributeService, AuthenticationService, IdentityAuthService}
import models.AccountDetails._
import scala.concurrent.Future

class AttributeController(payments: PaymentService, contacts: ContactRepository, attributes: AttributeService, mem: Membership, subs: DigitalPack) {
  
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = CloudWatch("AttributesController")

  private def lookup(endpointDescription: String, onSuccess: Attributes => Result, onNotFound: Result = notFound) = Action.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        attributes.get(id).map {
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

  def membershipDetails = paymentDetails(mem)
  def digitalPackDetails = paymentDetails(subs)

  def paymentDetails(product: ProductFamily) = Action.async { implicit request =>
    authenticationService.userId.fold[Future[Result]](Future(cookiesRequired)){ userId =>
      contacts.get(userId) flatMap { optContact =>
        optContact.fold[Future[Result]](Future(notFound)) { contact =>
          payments.paymentDetails(contact, product) map { paymentDetails =>
            (contact, paymentDetails).toResult
          }
        }
      }
    }.recover {
      case e:IllegalStateException => notFound
    }
  }
}
