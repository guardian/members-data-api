package controllers
import actions._
import play.api.libs.concurrent.Execution.Implicits._
import services.{AuthenticationService, IdentityAuthService}
import com.gu.memsub._
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.services.model.PaymentDetails
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import json.PaymentCardUpdateResultWriters._
import models.AccountDetails._
import models.ApiErrors._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.filters.cors.CORSActionBuilder

import scala.concurrent.Future
import scalaz.{-\/, EitherT, OptionT, \/, \/-}
import scalaz.std.scalaFuture._
import scalaz.syntax.std.option._

class AccountController extends LazyLogging {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val corsCardFilter = CORSActionBuilder(Config.mmaCardCorsConfig)
  lazy val mmaCorsFilter = CORSActionBuilder(Config.mmaCorsConfig)
  lazy val mmaAction = NoCacheAction andThen mmaCorsFilter andThen BackendFromCookieAction
  lazy val mmaCardAction = NoCacheAction andThen corsCardFilter andThen BackendFromCookieAction

  def updateCard[P <: SubscriptionPlan.AnyPlan : SubPlanReads] = mmaCardAction.async { implicit request =>
    val updateForm = Form { single("stripeToken" -> nonEmptyText) }
    val tp = request.touchpoint

    (for {
      user <- EitherT(Future.successful(authenticationService.userId \/> "no identity cookie for user"))
      sfUser <- EitherT(tp.contactRepo.get(user).map(_ \/> s"couldn't read contact from SF for $user (TODO check the separate ERROR to find out why)"))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      stripeCardToken <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no card token submitted with request"))
      updateResult <- EitherT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken).map(_ \/> "something missing when try to zuora payment card"))
    } yield updateResult match {
      case success: CardUpdateSuccess => Ok(Json.toJson(success))
      case failure: CardUpdateFailure => Forbidden(Json.toJson(failure))
    }).run.map {
      case -\/(message) =>
        logger.warn(s"didn't update card, $message")
        notFound
      case \/-(result) => result
    }
  }

  def paymentDetails[P <: SubscriptionPlan.Paid : SubPlanReads, F <: SubscriptionPlan.Free : SubPlanReads] = mmaAction.async { implicit request =>
    val maybeUserId = authenticationService.userId
    logger.info(s"Attempting to retrieve payment details for identity user: $maybeUserId")
    (for {
      user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
      contact <- EitherT(request.touchpoint.contactRepo.get(user).map(_ \/> s"couldn't read contact from SF for $user (TODO check the separate ERROR to find out why)"))
      sub <- EitherT(request.touchpoint.subService.either[F, P](contact).map(_ \/> s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} (TODO check the separate WARN to find out why)"))
      details <- EitherT(request.touchpoint.paymentService.paymentDetails(sub).map[\/[String, PaymentDetails]](\/.right))
    } yield (contact, details).toResult).run.map {
      case \/-(result) => {
        logger.info(s"Successfully retrieved payment details result for identity user: $maybeUserId")
        result
      }
      case -\/(message) => {
        logger.warn(s"Unable to retrieve payment details result for identity user $maybeUserId due to $message")
        Ok(Json.obj())
      }
    }
  }

  def membershipUpdateCard = updateCard[SubscriptionPlan.PaidMember]
  def digitalPackUpdateCard = updateCard[SubscriptionPlan.Digipack]

  def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  def monthlyContributionDetails = paymentDetails[SubscriptionPlan.Contributor, Nothing]
  def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
}
