package controllers

import com.gu.memsub._
import com.gu.services.model.PaymentDetails
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
import _root_.services.{AuthenticationService, IdentityAuthService}
import models.AccountDetails._
import scala.concurrent.Future
import scalaz.{\/, EitherT, OptionT}
import scalaz.std.scalaFuture._
import play.api.mvc.Results.{Ok, Forbidden}
import json.PaymentCardUpdateResultWriters._
import scalaz.syntax.std.option._


class AttributeController {
  
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val mmaCorsFilter = CORSActionBuilder(Config.mmaCorsConfig)

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val corsCardFilter = CORSActionBuilder(Config.mmaCardCorsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val mmaAction = mmaCorsFilter andThen BackendFromCookieAction
  lazy val mmaCardAction = corsCardFilter andThen BackendFromCookieAction
  lazy val metrics = CloudWatch("AttributesController")

  def updateAttributes() = BackendFromCookieAction.async { implicit request =>

    case class Err(cause: String)
    val NoSubscription = Err("No subscription")
    val NoSalesforce = Err("No contact")
    val NoIdentity = Err("No user")

    val res = (for {
      identityId <- EitherT(Future.successful(authenticationService.userId \/> NoIdentity))
      contact <- EitherT(request.touchpoint.contactRepo.get(identityId).map(_ \/> NoSalesforce))
      sub <- EitherT(request.touchpoint.membershipSubscriptionService.get(contact)(Membership).map(_ \/> NoSubscription))
      attrs = Attributes(identityId, sub.plan.tier.name, contact.regNumber)
      res <- EitherT(request.touchpoint.attrService.set(attrs).map(\/.right))
    } yield attrs).run

    res.map(_.fold[Result](e => ApiErrors.badRequest(e.cause), a => Ok(Json.obj("updated" -> true))))
       .recover { case _ => ApiErrors.internalError }
  }

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
  def digitalPackUpdateCard = updateCard(Digipack)

  def updateCard(implicit product: ProductFamily) = mmaCardAction.async { implicit request =>
    val updateForm = Form { single("stripeToken" -> nonEmptyText) }
    val tp = request.touchpoint

    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      sfUser <- OptionT(tp.contactRepo.get(user))
      subscription <- OptionT(tp.subService.get(sfUser))
      stripeCardToken <- OptionT(Future.successful(updateForm.bindFromRequest().value))
      updateResult <- OptionT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken))
    } yield updateResult match {
      case success: CardUpdateSuccess => Ok(Json.toJson(success))
      case failure: CardUpdateFailure => Forbidden(Json.toJson(failure))
    }).run.map(_.getOrElse(notFound))
  }

  def membershipDetails = paymentDetails(Membership)
  def digitalPackDetails = paymentDetails(Digipack)

  def paymentDetails(implicit product: ProductFamily) = mmaAction.async { implicit request =>
    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      contact <- OptionT(request.touchpoint.contactRepo.get(user))
      sub <- OptionT(request.touchpoint.subService.getEither(contact))
      details <- OptionT(request.touchpoint.paymentService.paymentDetails(sub).map[Option[PaymentDetails]](Some(_)))

    } yield (contact, details).toResult).run.map(_ getOrElse Ok(Json.obj()))
  }
}
