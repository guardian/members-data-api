package controllers

import com.gu.config.ProductFamily
import models.ApiError._
import models.ApiErrors._
import models.Features._
import actions._
import models._
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import services.{AuthenticationService, IdentityAuthService}
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

  def membershipDetails = paymentDetails(Membership)
  def digitalPackDetails = paymentDetails(DigitalPack)

  def paymentDetails(product: ProductFamilyName) = backendAction.async { implicit request =>

    val productFamily = request.touchpoint.ratePlanIds(product)
    authenticationService.userId.fold[Future[Result]](Future(cookiesRequired)){ userId =>
      request.touchpoint.contactRepo.get(userId) flatMap { optContact =>
        optContact.fold[Future[Result]](Future(notFound)) { contact =>
          request.touchpoint.paymentService.paymentDetails(contact, productFamily) map { paymentDetails =>
            (contact, paymentDetails).toResult
          }
        }
      }
    }.recover {
      case e:IllegalStateException => notFound
    }
  }
}
