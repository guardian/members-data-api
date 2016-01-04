package controllers

import com.gu.membership.{CardUpdateFailure, CardUpdateSuccess}
import configuration.Config
import models.ApiError._
import models.ApiErrors._
import models.Features._
import actions._
import models._
import monitoring.CloudWatch
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.Result
import play.filters.cors.CORSActionBuilder
import services.{AuthenticationService, IdentityAuthService}
import models.AccountDetails._
import scala.concurrent.Future
import scalaz.OptionT
import scalaz.std.scalaFuture._
import play.api.mvc.Results.{Ok, Forbidden}
import json.PaymentCardUpdateResultWriters._

class AttributeController {
  
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val mmaCorsFilter = CORSActionBuilder(Config.mmaCorsConfig)

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val corsCardFilter = CORSActionBuilder(Config.mmaCardCorsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val mmaAction = mmaCorsFilter andThen BackendFromCookieAction
  lazy val mmaCardAction = corsCardFilter andThen BackendFromCookieAction
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

  def membershipUpdateCard = updateCard(Membership)
  def digitalPackUpdateCard = updateCard(DigitalPack)

  def updateCard(product: ProductFamilyName) = mmaCardAction.async { implicit request =>
    val updateForm = Form { single("stripeToken" -> nonEmptyText) }
    val tp = request.touchpoint

    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      sfUser <- OptionT(tp.contactRepo.get(user))
      subscription <- OptionT(tp.subscriptionService.get(sfUser))
      stripeCardToken <- OptionT(Future.successful(updateForm.bindFromRequest().value))
      updateResult <- OptionT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken))
    } yield updateResult match {
      case success: CardUpdateSuccess => Ok(Json.toJson(success))
      case failure: CardUpdateFailure => Forbidden(Json.toJson(failure))
    }).run.map(_.getOrElse(notFound))
  }

  def membershipDetails = paymentDetails(Membership)
  def digitalPackDetails = paymentDetails(DigitalPack)

  def paymentDetails(product: ProductFamilyName) = mmaAction.async { implicit request =>
    val productFamily = request.touchpoint.productRatePlanIds(product)
    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      contact <- OptionT(request.touchpoint.contactRepo.get(user))
      details <- OptionT(request.touchpoint.paymentService.paymentDetails(contact, productFamily))
    } yield (contact, details).toResult).run.map(_ getOrElse Ok(Json.obj()))
  }
}
