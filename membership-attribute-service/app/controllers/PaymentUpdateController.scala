package controllers

import actions.{CommonActions, Return401IfNotSignedInRecently}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.zuora.api.GoCardlessZuoraInstance
import json.PaymentCardUpdateResultWriters._
import models.AccessScope.updateSelf
import models.subscription.subsv2.SubscriptionPlan
import models.subscription.{CardUpdateFailure, CardUpdateSuccess, GoCardless, PaymentMethod, Subscription}
import monitoring.CreateMetrics
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import scalaz.EitherT
import scalaz.std.scalaFuture._
import services.zuora.soap.models.Commands.{BankTransfer, CreatePaymentMethod}

import scala.concurrent.{ExecutionContext, Future}

class PaymentUpdateController(commonActions: CommonActions, override val controllerComponents: ControllerComponents, createMetrics: CreateMetrics)
    extends BaseController {
  import AccountHelpers._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  val metrics = createMetrics.forService(classOf[PaymentUpdateController])

  def updateCard(subscriptionName: String) =
    AuthorizeForRecentLogin(Return401IfNotSignedInRecently, requiredScopes = List(updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/update-card/:subscriptionName") {
        // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG
        val legacyForm = Form {
          tuple("stripeToken" -> nonEmptyText, "publicKey" -> nonEmptyText)
        }.bindFromRequest().value
        val updateForm = Form {
          tuple("stripePaymentMethodID" -> nonEmptyText, "stripePublicKey" -> nonEmptyText)
        }.bindFromRequest().value
        val tp = request.touchpoint
        val setPaymentCardFunction =
          if (updateForm.isDefined) tp.paymentService.setPaymentCardWithStripePaymentMethod _ else tp.paymentService.setPaymentCardWithStripeToken _
        val maybeUserId = request.redirectAdvice.userId
        SafeLogger.info(s"Attempting to update card for $maybeUserId")
        (for {
          user <- EitherT.fromEither(Future.successful(maybeUserId.toRight("no identity cookie for user")))
          stripeDetails <- EitherT.fromEither(
            Future.successful(updateForm.orElse(legacyForm).toRight("no 'stripePaymentMethodID' and 'stripePublicKey' submitted with request")),
          )
          (stripeCardIdentifier, stripePublicKey) = stripeDetails
          sfUser <- EitherT.fromEither(tp.contactRepository.get(user).map(_.toEither).map(_.flatMap(_.toRight(s"no SF user $user"))))
          subscription <- EitherT.fromEither(
            tp.subscriptionService
              .current[SubscriptionPlan.AnyPlan](sfUser)
              .map(subs => subscriptionSelector(Some(Subscription.Name(subscriptionName)), s"the sfUser $sfUser")(subs)),
          )
          stripeService <- EitherT.fromEither(
            Future
              .successful(tp.chooseStripe.serviceForPublicKey(stripePublicKey))
              .map(_.toRight(s"No Stripe service for public key: $stripePublicKey")),
          )
          updateResult <- EitherT.fromEither(
            setPaymentCardFunction(subscription.accountId, stripeCardIdentifier, stripeService).map(
              _.toRight("something was missing when attempting to update payment card in Zuora"),
            ),
          )
        } yield updateResult match {
          case success: CardUpdateSuccess => {
            SafeLogger.info(s"Successfully updated card for identity user: $user")
            Ok(Json.toJson(success))
          }
          case failure: CardUpdateFailure => {
            SafeLogger.error(scrub"Failed to update card for identity user: $user due to $failure")
            Forbidden(Json.toJson(failure))
          }
        }).run.map(_.toEither).map {
          case Left(message) =>
            SafeLogger.warn(s"Failed to update card for user $maybeUserId, due to $message")
            InternalServerError(s"Failed to update card for user $maybeUserId")
          case Right(result) => result
        }
      }
    }

  def updateDirectDebit(subscriptionName: String): Action[AnyContent] =
    AuthorizeForRecentLogin(Return401IfNotSignedInRecently, requiredScopes = List(updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/update-direct-debit/:subscriptionName") {
        // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG

        def checkDirectDebitUpdateResult(
            maybeUserId: Option[String],
            freshDefaultPaymentMethodOption: Option[PaymentMethod],
            bankAccountName: String,
            bankAccountNumber: String,
            bankSortCode: String,
        ) = freshDefaultPaymentMethodOption match {
          case Some(dd: GoCardless)
              if bankAccountName == dd.accountName &&
                dd.accountNumber.length > 3 && bankAccountNumber.endsWith(dd.accountNumber.substring(dd.accountNumber.length - 3)) &&
                bankSortCode == dd.sortCode =>
            SafeLogger.info(s"Successfully updated direct debit for identity user: $maybeUserId")
            Ok(
              Json.obj(
                "accountName" -> dd.accountName,
                "accountNumber" -> dd.accountNumber,
                "sortCode" -> dd.sortCode,
              ),
            )
          case Some(_) =>
            SafeLogger.error(scrub"New payment method for user $maybeUserId, does not match the posted Direct Debit details")
            InternalServerError("")
          case None =>
            SafeLogger.error(
              scrub"default-payment-method-lost: Default payment method for user $maybeUserId, was set to nothing, when attempting to update Direct Debit details",
            )
            InternalServerError("")
        }

        val updateForm = Form {
          tuple(
            "accountName" -> nonEmptyText,
            "accountNumber" -> nonEmptyText,
            "sortCode" -> nonEmptyText,
          )
        }

        val tp = request.touchpoint
        val maybeUserId = request.redirectAdvice.userId
        SafeLogger.info(s"Attempting to update direct debit for $maybeUserId")
        (for {
          user <- EitherT.fromEither(Future.successful(maybeUserId.toRight("no identity cookie for user")))
          directDebitDetails <- EitherT.fromEither(
            Future.successful(updateForm.bindFromRequest().value.toRight("no direct debit details submitted with request")),
          )
          (bankAccountName, bankAccountNumber, bankSortCode) = directDebitDetails
          sfUser <- EitherT.fromEither(tp.contactRepository.get(user).map(_.toEither.flatMap(_.toRight(s"no SF user $user"))))
          subscription <- EitherT.fromEither(
            tp.subscriptionService
              .current[SubscriptionPlan.AnyPlan](sfUser)
              .map(subs => subscriptionSelector(Some(Subscription.Name(subscriptionName)), s"the sfUser $sfUser")(subs)),
          )
          account <- EitherT.fromEither(
            annotateFailableFuture(tp.zuoraService.getAccount(subscription.accountId), s"get account with id ${subscription.accountId}"),
          )
          billToContact <- EitherT.fromEither(
            annotateFailableFuture(tp.zuoraService.getContact(account.billToId), s"get billTo contact with id ${account.billToId}"),
          )
          bankTransferPaymentMethod = BankTransfer(
            accountHolderName = bankAccountName,
            accountNumber = bankAccountNumber,
            sortCode = bankSortCode,
            firstName = billToContact.firstName,
            lastName = billToContact.lastName,
            countryCode = "GB",
          )
          createPaymentMethod = CreatePaymentMethod(
            accountId = subscription.accountId,
            paymentMethod = bankTransferPaymentMethod,
            paymentGateway = GoCardlessZuoraInstance,
            billtoContact = billToContact,
            invoiceTemplateOverride = None,
          )
          _ <- EitherT.fromEither(
            annotateFailableFuture(tp.zuoraService.createPaymentMethod(createPaymentMethod), "create direct debit payment method"),
          )
          freshDefaultPaymentMethodOption <- EitherT.fromEither(
            annotateFailableFuture(tp.paymentService.getPaymentMethod(subscription.accountId), "get fresh default payment method"),
          )
        } yield checkDirectDebitUpdateResult(maybeUserId, freshDefaultPaymentMethodOption, bankAccountName, bankAccountNumber, bankSortCode)).run
          .map(_.toEither)
          .map {
            case Left(message) =>
              SafeLogger.warn(s"default-payment-method-lost: failed to update direct debit for user $maybeUserId, due to $message")
              InternalServerError("")
            case Right(result) => result
          }
      }
    }

}
