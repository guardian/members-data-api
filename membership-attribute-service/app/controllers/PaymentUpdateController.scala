package controllers

import actions.{CommonActions, Return401IfNotSignedInRecently}
import com.gu.memsub
import com.gu.memsub.subsv2.SubscriptionPlan
import com.gu.memsub.{CardUpdateFailure, CardUpdateSuccess, GoCardless, PaymentMethod}
import com.gu.zuora.api.GoCardlessZuoraInstance
import com.gu.zuora.soap.models.Commands.{BankTransfer, CreatePaymentMethod}
import json.PaymentCardUpdateResultWriters._
import models.AccessScope.{readSelf, updateSelf}
import monitoring.CreateMetrics
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Result}
import scalaz.EitherT
import scalaz.std.scalaFuture._
import services.mail.{EmailData, SendEmail}
import utils.Sanitizer.Sanitizer
import utils.{SanitizedLogging, SimpleEitherT}

import scala.concurrent.{ExecutionContext, Future}

class PaymentUpdateController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents,
    sendEmail: SendEmail,
    createMetrics: CreateMetrics,
) extends BaseController
    with SanitizedLogging {
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
        logger.info(s"Attempting to update card for $maybeUserId")
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
              .map(subs => subscriptionSelector(Some(memsub.Subscription.Name(subscriptionName)), s"the sfUser $sfUser")(subs)),
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
            logger.info(s"Successfully updated card for identity user: $user")
            Ok(Json.toJson(success))
          }
          case failure: CardUpdateFailure => {
            logError(scrub"Failed to update card for identity user: $user due to $failure")
            Forbidden(Json.toJson(failure))
          }
        }).run.map(_.toEither).map {
          case Left(message) =>
            logger.warn(s"Failed to update card for user $maybeUserId, due to $message")
            InternalServerError(s"Failed to update card for user $maybeUserId")
          case Right(result) => result
        }
      }
    }

  private def checkDirectDebitUpdateResult(
      userId: String,
      freshDefaultPaymentMethodOption: Option[PaymentMethod],
      bankAccountName: String,
      bankAccountNumber: String,
      bankSortCode: String,
  ): Result = freshDefaultPaymentMethodOption match {
    case Some(dd: GoCardless)
        if bankAccountName == dd.accountName &&
          dd.accountNumber.length > 3 && bankAccountNumber.endsWith(dd.accountNumber.substring(dd.accountNumber.length - 3)) &&
          bankSortCode == dd.sortCode =>
      logger.info(s"Successfully updated direct debit for identity user: $userId")
      Ok(
        Json.obj(
          "accountName" -> dd.accountName,
          "accountNumber" -> dd.accountNumber,
          "sortCode" -> dd.sortCode,
        ),
      )
    case Some(_) =>
      logError(scrub"New payment method for user $userId, does not match the posted Direct Debit details")
      InternalServerError("")
    case None =>
      logError(
        scrub"default-payment-method-lost: Default payment method for user $userId, was set to nothing, when attempting to update Direct Debit details",
      )
      InternalServerError("")
  }

  def updateDirectDebit(subscriptionName: String): Action[AnyContent] =
    AuthorizeForRecentLoginAndScopes(Return401IfNotSignedInRecently, requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/update-direct-debit/:subscriptionName") {
        // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG

        val updateForm = Form {
          tuple(
            "accountName" -> nonEmptyText,
            "accountNumber" -> nonEmptyText,
            "sortCode" -> nonEmptyText,
          )
        }

        val tp = request.touchpoint
        val user = request.user
        val userId = user.identityId

        logger.info(s"Attempting to update direct debit for $userId")

        (for {
          directDebitDetails <- SimpleEitherT.fromEither(updateForm.bindFromRequest().value.toRight("no direct debit details submitted with request"))
          (bankAccountName, bankAccountNumber, bankSortCode) = directDebitDetails
          contact <- SimpleEitherT(tp.contactRepository.get(userId).map(_.toEither.flatMap(_.toRight(s"no SF user $userId"))))
          subscription <- SimpleEitherT(
            tp.subscriptionService
              .current[SubscriptionPlan.AnyPlan](contact)
              .map(subs => subscriptionSelector(Some(memsub.Subscription.Name(subscriptionName)), s"the sfUser $contact")(subs)),
          )
          account <- SimpleEitherT(
            annotateFailableFuture(tp.zuoraSoapService.getAccount(subscription.accountId), s"get account with id ${subscription.accountId}"),
          )
          billToContact <- SimpleEitherT(
            annotateFailableFuture(tp.zuoraSoapService.getContact(account.billToId), s"get billTo contact with id ${account.billToId}"),
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
          _ <- SimpleEitherT(
            annotateFailableFuture(tp.zuoraSoapService.createPaymentMethod(createPaymentMethod), "create direct debit payment method"),
          )
          freshDefaultPaymentMethodOption <- SimpleEitherT(
            annotateFailableFuture(tp.paymentService.getPaymentMethod(subscription.accountId), "get fresh default payment method"),
          )
          _ <- SimpleEitherT.rightT(
            sendEmail(
              EmailData(
                emailAddress = user.primaryEmailAddress,
                salesforceContactId = contact.salesforceContactId,
                campaignName = "payment-method-changed-email",
                dataPoints = Map(
                  "first_name" -> contact.firstName.getOrElse(""),
                  "last_name" -> contact.lastName,
                  "payment_method" -> "direct_debit",
                ),
              ),
            ),
          )
        } yield checkDirectDebitUpdateResult(userId, freshDefaultPaymentMethodOption, bankAccountName, bankAccountNumber, bankSortCode)).run
          .map(_.toEither)
          .map {
            case Left(message) =>
              logger.error(s"Failed to update direct debit for user $userId, due to $message")
              InternalServerError("")
            case Right(result) => result
          }
      }
    }

}
