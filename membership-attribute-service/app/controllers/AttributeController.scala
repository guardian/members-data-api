package controllers

import actions.WithBackendFromCookieAction
import com.gu.config.ProductFamily
import com.gu.config.{DigitalPack, Membership, ProductFamily}
import com.gu.membership.salesforce.ContactRepository
import com.gu.services.PaymentService
import components.TouchpointComponents
import play.api.mvc.Action
import models.ApiError._
import models.ApiErrors._
import models.Features._
import actions._
import models.{Attributes, Features}
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import services.{AttributeService, AuthenticationService, IdentityAuthService}
import models.AccountDetails._
import scala.concurrent.Future

class AttributeController {
  
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val backendAction = BackendFromCookieAction
  lazy val metrics = CloudWatch("AttributesController")

  private def lookup(endpointDescription: String, onSuccess: Attributes => Result, onNotFound: Result = notFound) = backendAction.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        request.touchpoint.attrService.get(id).map {
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

  def membershipDetails = paymentDetails({t: TouchpointComponents => t.membershipPlans})
  def digitalPackDetails = paymentDetails({t: TouchpointComponents => t.digitalPackPlans})

  def paymentDetails(func: (TouchpointComponents)=>ProductFamily) = backendAction.async { implicit request =>
    authenticationService.userId.fold[Future[Result]](Future(cookiesRequired)){ userId =>
      request.touchpoint.contactRepo.get(userId) flatMap { optContact =>
        optContact.fold[Future[Result]](Future(notFound)) { contact =>
          request.touchpoint.paymentService.paymentDetails(contact, func(request.touchpoint)) map { paymentDetails =>
            (contact, paymentDetails).toResult
          }
        }
      }
    }.recover {
      case e:IllegalStateException => notFound
    }
  }
}
