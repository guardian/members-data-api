package controllers
import play.api.libs.concurrent.Execution.Implicits._
import services.{AuthenticationService, IdentityAuthService}
import com.gu.memsub._
import json.PaymentCardUpdateResultWriters._
import com.gu.services.model.PaymentDetails
import configuration.Config
import models.ApiErrors._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.filters.cors.CORSActionBuilder
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.SubscriptionPlan
import scalaz.std.scalaFuture._
import scala.concurrent.Future
import models.AccountDetails._
import scalaz.OptionT
import actions._
import com.gu.memsub.subsv2.reads.SubPlanReads


class AccountController {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val corsCardFilter = CORSActionBuilder(Config.mmaCardCorsConfig)
  lazy val mmaCorsFilter = CORSActionBuilder(Config.mmaCorsConfig)
  lazy val mmaAction = mmaCorsFilter andThen BackendFromCookieAction
  lazy val mmaCardAction = corsCardFilter andThen BackendFromCookieAction

  def updateCard[P <: SubscriptionPlan.AnyPlan : SubPlanReads] = mmaCardAction.async { implicit request =>
    val updateForm = Form { single("stripeToken" -> nonEmptyText) }
    val tp = request.touchpoint

    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      sfUser <- OptionT(tp.contactRepo.get(user))
      subscription <- OptionT(tp.subService.current[P](sfUser).map(_.headOption))
      stripeCardToken <- OptionT(Future.successful(updateForm.bindFromRequest().value))
      updateResult <- OptionT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken))
    } yield updateResult match {
      case success: CardUpdateSuccess => Ok(Json.toJson(success))
      case failure: CardUpdateFailure => Forbidden(Json.toJson(failure))
    }).run.map(_.getOrElse(notFound))
  }

  def paymentDetails[P <: SubscriptionPlan.Paid : SubPlanReads, F <: SubscriptionPlan.Free : SubPlanReads] = mmaAction.async { implicit request =>
    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      contact <- OptionT(request.touchpoint.contactRepo.get(user))
      sub <- OptionT(request.touchpoint.subService.either[F, P](contact))
      details <- OptionT(request.touchpoint.paymentService.paymentDetails(sub).map[Option[PaymentDetails]](Some(_)))
    } yield (contact, details).toResult).run.map(_ getOrElse Ok(Json.obj()))
  }

  def membershipUpdateCard = updateCard[SubscriptionPlan.PaidMember]
  def digitalPackUpdateCard = updateCard[SubscriptionPlan.Digipack]

  def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
}
