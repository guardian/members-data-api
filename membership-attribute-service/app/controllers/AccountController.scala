package controllers
import actions._
import services.PaymentFailureAlerter._
import services.{AuthenticationService, IdentityAuthService}
import com.gu.memsub._
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{PaidChargeList, Subscription, SubscriptionPlan}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.salesforce.SimpleContactRepository
import com.gu.services.model.PaymentDetails
import com.gu.stripe.{Stripe, StripeService}
import com.gu.zuora.api.RegionalStripeGateways
import com.gu.zuora.rest.ZuoraRestService.PaymentMethodId
import com.gu.zuora.soap.models.Commands.{BankTransfer, CreatePaymentMethod}
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
import json.PaymentCardUpdateResultWriters._
import models.AccountDetails._
import models.ApiErrors._
import models.{AccountDetails, ApiError}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.{BaseController, ControllerComponents}
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.{-\/, EitherT, ListT, OptionT, \/, \/-}
import utils.{ListEither, OptionEither}

import scala.concurrent.{ExecutionContext, Future}

class AccountController(commonActions: CommonActions, override val controllerComponents: ControllerComponents) extends BaseController with LazyLogging {
  import commonActions._
  implicit val executionContext: ExecutionContext= controllerComponents.executionContext
  lazy val authenticationService: AuthenticationService = IdentityAuthService

   def cancelSubscription [P <: SubscriptionPlan.AnyPlan : SubPlanReads] = BackendFromCookieAction.async { implicit request =>

    val tp = request.touchpoint
    val cancelForm = Form { single("reason" -> nonEmptyText) }
    val maybeUserId = authenticationService.userId

    def handleInputBody(cancelForm: Form[String]): Future[ApiError \/ String] = Future.successful {
      cancelForm.bindFromRequest().value.map { cancellationReason =>
        \/-(cancellationReason)
      }.getOrElse {
        logger.warn("No reason for cancellation was submitted with the request.")
        -\/(badRequest("Malformed request. Expected a valid reason for cancellation."))
      }
    }

    def retrieveZuoraSubscription(user: String): Future[ApiError \/ Subscription[P]] = {
      val getSubscriptionData = for {
        sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"No Salesforce user: $user")))
        zuoraSubscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"No current subscriptions for the Salesforce user: $sfUser"))
      } yield zuoraSubscription

      getSubscriptionData.run.map {
        case -\/(message) =>
          logger.warn(s"Failed to retrieve subscription information for user $maybeUserId, due to: $message")
          -\/(notFound)
        case \/-(subscription) =>
          \/-(subscription)
      }
    }

    def executeCancellation(zuoraSubscription: Subscription[P], reason: String): Future[ApiError \/ Unit] = {
      val cancellationSteps = for {
        _ <- EitherT(tp.zuoraRestService.disableAutoPay(zuoraSubscription.accountId)).leftMap(message => s"Error while trying to disable AutoPay: $message")
        _ <- EitherT(tp.zuoraRestService.updateCancellationReason(zuoraSubscription.name, reason)).leftMap(message => s"Error while updating cancellation reason: $message")
        cancelResult <- EitherT(tp.zuoraRestService.cancelSubscription(zuoraSubscription.name)).leftMap(message => s"Error while cancelling subscription: $message")
      } yield cancelResult

      cancellationSteps.run.map {
        case -\/(message) =>
          logger.warn(s"Failed to execute zuora cancellation steps for user $maybeUserId, due to: $message")
          -\/(notFound)
        case \/-(()) => \/-(())
      }
    }

    logger.info(s"Attempting to cancel contribution for $maybeUserId")

    (for {
      user <- EitherT(Future.successful(maybeUserId \/> unauthorized))
      cancellationReason <- EitherT(handleInputBody(cancelForm))
      zuoraSubscription <- EitherT(retrieveZuoraSubscription(user))
      cancellation <- EitherT(executeCancellation(zuoraSubscription, cancellationReason))
    } yield cancellation).run.map {
      case -\/(apiError) =>
        SafeLogger.error(scrub"Failed to cancel subscription for user $maybeUserId")
        apiError
      case \/-(_) =>
        logger.info(s"Successfully cancelled subscription for user $maybeUserId")
        Ok
    }
  }

  private def annotateFailableFuture[SuccessValue](failableFuture: Future[SuccessValue], action: String): Future[String \/ SuccessValue] =
    failableFuture.map(\/.right).recover {
      case exception => \/.left(s"failed to $action. Exception : $exception")
    }

  private def updateDirectDebit[P <: SubscriptionPlan.AnyPlan : SubPlanReads] = BackendFromCookieAction.async { implicit request =>
    // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG

    def checkDirectDebitUpdateResult(
                                      maybeUserId: Option[String],
                                      freshDefaultPaymentMethodOption: Option[PaymentMethod],
                                      bankAccountName: String,
                                      bankAccountNumber: String,
                                      bankSortCode: String) = freshDefaultPaymentMethodOption match {
      case Some(dd: GoCardless)
        if bankAccountName == dd.accountName &&
          dd.accountNumber.length>3 && bankAccountNumber.endsWith(dd.accountNumber.substring(dd.accountNumber.length - 3)) &&
          bankSortCode == dd.sortCode =>
        logger.info(s"Successfully updated direct debit for identity user: $maybeUserId")
        Ok(Json.obj(
          "accountName" -> dd.accountName,
          "accountNumber" -> dd.accountNumber,
          "sortCode" -> dd.sortCode
        ))
      case Some(_) =>
        logger.error(s"New payment method for user $maybeUserId, does not match the posted Direct Debit details")
        InternalServerError("")
      case None =>
        logger.error(s"default-payment-method-lost: Default payment method for user $maybeUserId, was set to nothing, when attempting to update Direct Debit details")
        InternalServerError("")
    }

    val updateForm = Form { tuple(
      "accountName" -> nonEmptyText,
      "accountNumber" -> nonEmptyText,
      "sortCode" -> nonEmptyText
    ) }

    val tp = request.touchpoint
    val maybeUserId = authenticationService.userId
    logger.info(s"Attempting to update direct debit for $maybeUserId")
    (for {
      user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
      directDebitDetails <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no direct debit details submitted with request"))
      (bankAccountName, bankAccountNumber, bankSortCode) = directDebitDetails
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      account <- EitherT(annotateFailableFuture(tp.zuoraService.getAccount(subscription.accountId), s"get account with id ${subscription.accountId}"))
      billToContact <- EitherT(annotateFailableFuture(tp.zuoraService.getContact(account.billToId), s"get billTo contact with id ${account.billToId}"))
      bankTransferPaymentMethod = BankTransfer(
        accountHolderName = bankAccountName,
        accountNumber = bankAccountNumber,
        sortCode = bankSortCode,
        firstName = billToContact.firstName,
        lastName = billToContact.lastName,
        countryCode = "GB"
      )
      createPaymentMethod = CreatePaymentMethod(
        accountId = subscription.accountId,
        paymentMethod = bankTransferPaymentMethod,
        paymentGateway = account.paymentGateway.get, // this will need to change to use this endpoint for 'payment method' SWITCH
        billtoContact = billToContact,
        invoiceTemplateOverride = None
      )
      _ <- EitherT(annotateFailableFuture(tp.zuoraService.createPaymentMethod(createPaymentMethod), "create direct debit payment method"))
      freshDefaultPaymentMethodOption <- EitherT(annotateFailableFuture(tp.paymentService.getPaymentMethod(subscription.accountId), "get fresh default payment method"))
    } yield checkDirectDebitUpdateResult(maybeUserId, freshDefaultPaymentMethodOption, bankAccountName, bankAccountNumber, bankSortCode)).run.map {
      case -\/(message) =>
        logger.warn(s"default-payment-method-lost: failed to update direct debit for user $maybeUserId, due to $message")
        InternalServerError("")
      case \/-(result) => result
    }

  }

  private def updateCard[P <: SubscriptionPlan.AnyPlan : SubPlanReads] = BackendFromCookieAction.async { implicit request =>
    // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG

    val updateForm = Form { tuple("stripeToken" -> nonEmptyText, "publicKey" -> text) }
    val tp = request.touchpoint
    val maybeUserId = authenticationService.userId
    logger.info(s"Attempting to update card for $maybeUserId")
    (for {
      user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
      stripeDetails <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no card token and public key submitted with request"))
      (stripeCardToken, stripePublicKey) = stripeDetails
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      stripeService <- EitherT(Future.successful(tp.stripeServicesByPublicKey.get(stripePublicKey)).map(_ \/> s"No Stripe service for public key: $stripePublicKey"))
      updateResult <- EitherT(tp.paymentService.setPaymentCardWithStripeToken(subscription.accountId, stripeCardToken, stripeService).map(_ \/> "something was missing when attempting to update payment card in Zuora"))
    } yield updateResult match {
      case success: CardUpdateSuccess => {
        logger.info(s"Successfully updated card for identity user: $user")
        Ok(Json.toJson(success))
      }
      case failure: CardUpdateFailure => {
        SafeLogger.error(scrub"Failed to update card for identity user: $user due to $failure")
        Forbidden(Json.toJson(failure))
      }
    }).run.map {
      case -\/(message) =>
        logger.warn(s"Failed to update card for user $maybeUserId, due to $message")
        InternalServerError(s"Failed to update card for user $maybeUserId")
      case \/-(result) => result
    }
  }

  private def findStripeCustomer(customerId: String, likelyStripeService: StripeService)(implicit tp: TouchpointComponents): Future[Option[Stripe.Customer]] = {
    val alternativeStripeService = if (likelyStripeService == tp.ukStripeService) tp.auStripeService else tp.ukStripeService
    likelyStripeService.Customer.read(customerId).recoverWith {
      case _ => alternativeStripeService.Customer.read(customerId)
    } map(Option(_)) recover {
      case _ => None
    }
  }

  private def getUpToDatePaymentDetailsFromStripe(accountId: com.gu.memsub.Subscription.AccountId, paymentDetails: PaymentDetails)(implicit tp: TouchpointComponents): Future[PaymentDetails] = {
    paymentDetails.paymentMethod.map {
      case card: PaymentCard =>
        def liftFuture[A](m: Option[A]): OptionT[Future, A] = OptionT(Future.successful(m))
        (for {
          account <- tp.zuoraService.getAccount(accountId).liftM[OptionT]
          defaultPaymentMethodId <- liftFuture(account.defaultPaymentMethodId.map(_.trim).filter(_.nonEmpty))
          zuoraPaymentMethod <- tp.zuoraService.getPaymentMethod(defaultPaymentMethodId).liftM[OptionT]
          customerId <- liftFuture(zuoraPaymentMethod.secondTokenId.map(_.trim).filter(_.startsWith("cus_")))
          paymentGateway <- liftFuture(account.paymentGateway)
          stripeService <- liftFuture(tp.stripeServicesByPaymentGateway.get(paymentGateway))
          stripeCustomer <- OptionT(findStripeCustomer(customerId, stripeService))
          stripeCard = stripeCustomer.card
        } yield {
          // TODO consider broadcasting to a queue somewhere iff the payment method in Zuora is out of date compared to Stripe
          card.copy(
            cardType = Some(stripeCard.`type`),
            paymentCardDetails = Some(PaymentCardDetails(stripeCard.last4, stripeCard.exp_month, stripeCard.exp_year))
          )
        }).run
      case x => Future.successful(None) // not updated
    }.sequence.map { maybeUpdatedPaymentCard =>
      paymentDetails.copy(paymentMethod = maybeUpdatedPaymentCard.flatten orElse paymentDetails.paymentMethod)
    }
  }

  private def paymentDetails[P <: SubscriptionPlan.Paid : SubPlanReads, F <: SubscriptionPlan.Free : SubPlanReads] = BackendFromCookieAction.async { implicit request =>
    implicit val tp = request.touchpoint
    def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get)
    val maybeUserId = authenticationService.userId

    logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
    (for {
      user <- OptionEither.liftFutureEither(maybeUserId)
      contact <- OptionEither(tp.contactRepo.get(user))
      freeOrPaidSub <- OptionEither(tp.subService.either[F, P](contact).map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message")))
      sub = freeOrPaidSub.fold(identity, identity)
      paymentDetails <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right).recover { case x => \/.left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x") })
      upToDatePaymentDetails <- OptionEither.liftOption(getUpToDatePaymentDetailsFromStripe(sub.accountId, paymentDetails).map(\/.right).recover { case x => \/.left(s"error getting up-to-date card details for payment method of account: ${sub.accountId}. Reason: $x") })
      accountSummary <- OptionEither.liftOption(tp.zuoraRestService.getAccount(sub.accountId).recover { case x => \/.left(s"error receiving account summary for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x") })
      stripeService = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry).flatMap(tp.stripeServicesByPaymentGateway.get).getOrElse(tp.ukStripeService)
      alertText <- OptionEither.liftEitherOption(alertText(accountSummary, sub, getPaymentMethod))
    } yield AccountDetails(contact.regNumber, accountSummary.billToContact.email, upToDatePaymentDetails, stripeService.publicKey, alertText).toJson).run.run.map {
      case \/-(Some(result)) =>
        logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
        Ok(result)
      case \/-(None) =>
        logger.info(s"identity user doesn't exist in SF: ${maybeUserId.mkString}")
        Ok(Json.obj())
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve payment details due to an internal error")
    }
  }


  def allSubscriptions(
    contactRepo: SimpleContactRepository,
    subService: SubscriptionService[Future]
  )(
    maybeUserId: Option[String]
  ): OptionT[OptionEither.FutureEither, List[Subscription[SubscriptionPlan.AnyPlan]]] = for {
    user <- OptionEither.liftFutureEither(maybeUserId)
    contact <- OptionEither(contactRepo.get(user))
    subscriptions <- OptionEither.liftEitherOption(subService.current[SubscriptionPlan.AnyPlan](contact)) // TODO are we happy with an empty list in case of error ?!?!
  } yield subscriptions

  def allPaymentDetails = BackendFromCookieAction.async { implicit request =>
    implicit val tp = request.touchpoint
    def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get)
    val maybeUserId = authenticationService.userId

    logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
    (for {
      subscription <- ListEither.fromOptionEither(allSubscriptions(tp.contactRepo, tp.subService)(maybeUserId))
      freeOrPaidSub = subscription.plan.charges match {
        case _: PaidChargeList => \/.right(subscription.asInstanceOf[Subscription[SubscriptionPlan.Paid]])
        case _ => \/.left(subscription.asInstanceOf[Subscription[SubscriptionPlan.Free]])
      }
      paymentDetails <- ListEither.liftList(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right).recover { case x => \/.left(s"error retrieving payment details for subscription: ${subscription.name}. Reason: $x") })
      upToDatePaymentDetails <- ListEither.liftList(getUpToDatePaymentDetailsFromStripe(subscription.accountId, paymentDetails).map(\/.right).recover { case x => \/.left(s"error getting up-to-date card details for payment method of account: ${subscription.accountId}. Reason: $x") })
      accountSummary <- ListEither.liftList(tp.zuoraRestService.getAccount(subscription.accountId).recover { case x => \/.left(s"error receiving account summary for subscription: ${subscription.name} with account id ${subscription.accountId}. Reason: $x") })
      stripeService = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry).flatMap(tp.stripeServicesByPaymentGateway.get).getOrElse(tp.ukStripeService)
      alertText <- ListEither.liftEitherList(alertText(accountSummary, subscription, getPaymentMethod))
    } yield AccountDetails(None, accountSummary.billToContact.email, upToDatePaymentDetails, stripeService.publicKey, alertText).toJson).run.run.map {
      case \/-(subscriptionJSONs) =>
        logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
        Ok(Json.toJson(subscriptionJSONs))
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve payment details due to an internal error")
    }
  }

  private def updateContributionAmount[P <: SubscriptionPlan.Paid : SubPlanReads] = BackendFromCookieAction.async { implicit request =>
    val updateForm = Form { single("newPaymentAmount" -> bigDecimal(5, 2)) }
    val tp = request.touchpoint
    val maybeUserId = authenticationService.userId
    logger.info(s"Attempting to update contribution amount for ${maybeUserId.mkString}")
    (for {
      newPrice <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no new payment amount submitted with request"))
      user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[P](sfUser).map(_.headOption).map (_ \/> s"no current subscriptions for the sfUser $sfUser"))
      applyFromDate = subscription.plan.chargedThrough.getOrElse(subscription.plan.start)
      currencyGlyph = subscription.plan.charges.price.prices.head.currency.glyph
      oldPrice = subscription.plan.charges.price.prices.head.amount
      reasonForChange = s"User updated contribution via self-service MMA. Amount changed from $currencyGlyph$oldPrice to $currencyGlyph$newPrice effective from $applyFromDate"
      result <- EitherT(tp.zuoraRestService.updateChargeAmount(subscription.name, subscription.plan.charges.subRatePlanChargeId, subscription.plan.id, newPrice.toDouble, reasonForChange, applyFromDate)).leftMap(message => s"Error while updating contribution amount: $message")
    } yield result).run map { _ match {
        case -\/(message) =>
          SafeLogger.error(scrub"Failed to update payment amount for user ${maybeUserId.mkString}, due to: $message")
          InternalServerError(message)
        case \/-(()) =>
          logger.info(s"Contribution amount updated for user ${maybeUserId.mkString}")
          Ok("Success")
      }
    }
  }

  def cancelRegularContribution = cancelSubscription[SubscriptionPlan.Contributor]
  def cancelMembership = cancelSubscription[SubscriptionPlan.Member]

  def membershipUpdateCard = updateCard[SubscriptionPlan.PaidMember]
  def digitalPackUpdateCard = updateCard[SubscriptionPlan.Digipack]
  def paperUpdateCard = updateCard[SubscriptionPlan.PaperPlan]
  def contributionUpdateCard = updateCard[SubscriptionPlan.Contributor]

  def contributionUpdateAmount = updateContributionAmount[SubscriptionPlan.Contributor]

  def contributionUpdateDirectDebit = updateDirectDebit[SubscriptionPlan.Contributor]
  def paperUpdateDirectDebit = updateDirectDebit[SubscriptionPlan.PaperPlan]

  def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  def monthlyContributionDetails = paymentDetails[SubscriptionPlan.Contributor, Nothing]
  def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
  def paperDetails = paymentDetails[SubscriptionPlan.PaperPlan, Nothing]

}