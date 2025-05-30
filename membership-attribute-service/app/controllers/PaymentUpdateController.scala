package controllers

import actions.{CommonActions, Return401IfNotSignedInRecently}
import com.gu.memsub
import com.gu.memsub.subsv2.ProductType
import com.gu.memsub.{CardUpdateFailure, CardUpdateSuccess, GoCardless, PaymentMethod}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.Contact
import com.gu.zuora.api.GoCardlessGateway
import com.gu.zuora.api.GoCardlessTortoiseMediaGateway
import com.gu.zuora.soap.models.Commands.{BankTransfer, CreatePaymentMethod}
import json.PaymentCardUpdateResultWriters._
import models.AccessScope.{readSelf, updateSelf}
import monitoring.CreateMetrics
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc._
import scalaz.EitherT
import scalaz.std.scalaFuture._
import services.mail.Emails.paymentMethodChangedEmail
import services.mail.{Card, DirectDebit, PaymentType, SendEmail}
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT
import models.GatewayOwner

import scala.concurrent.{ExecutionContext, Future}

class PaymentUpdateController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents,
    sendEmail: SendEmail,
    createMetrics: CreateMetrics,
) extends BaseController
    with SafeLogging {
  import AccountHelpers._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  val metrics = createMetrics.forService(classOf[PaymentUpdateController])

  def updateCard(subscriptionName: String) =
    AuthorizeForRecentLoginAndScopes(Return401IfNotSignedInRecently, requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("POST /user-attributes/me/update-card/:subscriptionName") {
        // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG
        val legacyForm = Form {
          tuple("stripeToken" -> nonEmptyText, "publicKey" -> nonEmptyText)
        }.bindFromRequest().value
        val updateForm = Form {
          tuple("stripePaymentMethodID" -> nonEmptyText, "stripePublicKey" -> nonEmptyText)
        }.bindFromRequest().value
        val services = request.touchpoint
        val useStripePaymentMethod = updateForm.isDefined
        val user = request.user
        val userId = user.identityId
        logger.info(s"Attempting to update card for $userId")
        (for {
          stripeDetails <- EitherT.fromEither(
            Future.successful(updateForm.orElse(legacyForm).toRight("no 'stripePaymentMethodID' and 'stripePublicKey' submitted with request")),
          )
          contact <- EitherT.fromEither(services.contactRepository.get(userId).map(_.toEither).map(_.flatMap(_.toRight(s"no SF user $userId"))))
          subscription <- EitherT.fromEither(
            services.subscriptionService
              .current(contact)
              .map(subs => subscriptionSelector(memsub.Subscription.SubscriptionNumber(subscriptionName), s"the sfUser $contact", subs)),
          )
          (stripeCardIdentifier, stripePublicKey) = stripeDetails
          updateResult <- services
            .setPaymentCard(stripePublicKey)
            .setPaymentCard(useStripePaymentMethod, subscription.accountId, stripeCardIdentifier)
          catalog <- SimpleEitherT.rightT(services.futureCatalog)
          productType = subscription.plan(catalog).productType(catalog)
          _ <- sendPaymentMethodChangedEmail(user.primaryEmailAddress, contact, Card, productType)
        } yield updateResult match {
          case success: CardUpdateSuccess => {
            logger.info(s"Successfully updated card for identity user: $user")
            Ok(Json.toJson(success))
          }
          case failure: CardUpdateFailure => {
            logger.error(scrub"Failed to update card for identity user: $user due to $failure")
            Forbidden(Json.toJson(failure))
          }
        }).run.map(_.toEither).map {
          case Left(message) =>
            logger.warn(s"Failed to update card for user $userId, due to $message")
            InternalServerError(s"Failed to update card for user $userId")
          case Right(result) => result
        }
      }
    }

  private def sendPaymentMethodChangedEmail(
      emailAddress: String,
      contact: Contact,
      paymentMethod: PaymentType,
      productType: ProductType,
  )(implicit logPrefix: LogPrefix): SimpleEitherT[Unit] =
    SimpleEitherT.rightT(sendEmail.send(paymentMethodChangedEmail(emailAddress, contact, paymentMethod, productType)))

  private def checkDirectDebitUpdateResult(
      freshDefaultPaymentMethodOption: Option[PaymentMethod],
      bankAccountName: String,
      bankAccountNumber: String,
      bankSortCode: String,
  )(implicit logPrefix: LogPrefix): Result = freshDefaultPaymentMethodOption match {
    case Some(dd: GoCardless)
        if bankAccountName == dd.accountName &&
          dd.accountNumber.length > 3 && bankAccountNumber.endsWith(dd.accountNumber.substring(dd.accountNumber.length - 3)) &&
          bankSortCode == dd.sortCode =>
      logger.info(s"Successfully updated direct debit")
      Ok(
        Json.obj(
          "accountName" -> dd.accountName,
          "accountNumber" -> dd.accountNumber,
          "sortCode" -> dd.sortCode,
        ),
      )
    case Some(_) =>
      logger.error(
        scrub"New payment method $freshDefaultPaymentMethodOption, does not match the posted Direct Debit details $bankSortCode $bankAccountNumber $bankAccountName",
      )
      InternalServerError("")
    case None =>
      logger.error(
        scrub"default-payment-method-lost: Default payment method was set to nothing, when attempting to update Direct Debit details",
      )
      InternalServerError("")
  }

  def updateDirectDebit(subscriptionName: String): Action[AnyContent] =
    AuthorizeForRecentLoginAndScopes(Return401IfNotSignedInRecently, requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("POST /user-attributes/me/update-direct-debit/:subscriptionName") {
        // TODO - refactor to use the Zuora-only based lookup, like in AttributeController.pickAttributes - https://trello.com/c/RlESb8jG

        val updateForm = Form {
          tuple(
            "accountName" -> nonEmptyText,
            "accountNumber" -> nonEmptyText,
            "sortCode" -> nonEmptyText,
            "gatewayOwner" -> optional(text).transform[GatewayOwner](
              GatewayOwner.fromString,
              _.value,
            ),
          )
        }

        val services = request.touchpoint
        val user = request.user
        val userId = user.identityId

        logger.info(s"Attempting to update direct debit")

        (for {
          directDebitDetails <- SimpleEitherT.fromEither(updateForm.bindFromRequest().value.toRight("no direct debit details submitted with request"))
          (bankAccountName, bankAccountNumber, bankSortCode, paymentGatewayOwner) = directDebitDetails
          contact <- SimpleEitherT(services.contactRepository.get(userId).map(_.toEither.flatMap(_.toRight(s"no SF user for $userId"))))
          subscription <- SimpleEitherT(
            services.subscriptionService
              .current(contact)
              .map(subs => subscriptionSelector(memsub.Subscription.SubscriptionNumber(subscriptionName), s"the sfUser $contact", subs)),
          )
          account <- SimpleEitherT(
            annotateFailableFuture(services.zuoraSoapService.getAccount(subscription.accountId), s"get account with id ${subscription.accountId}"),
          )
          billToContact <- SimpleEitherT(
            annotateFailableFuture(services.zuoraSoapService.getContact(account.billToId), s"get billTo contact with id ${account.billToId}"),
          )
          bankTransferPaymentMethod = BankTransfer(
            accountHolderName = bankAccountName,
            accountNumber = bankAccountNumber,
            sortCode = bankSortCode,
            firstName = billToContact.firstName,
            lastName = billToContact.lastName,
            countryCode = "GB",
          )
          paymentGatewayToUse = paymentGatewayOwner match {
            case GatewayOwner.TortoiseMedia => GoCardlessTortoiseMediaGateway
            case _ => GoCardlessGateway
          }
          createPaymentMethod = CreatePaymentMethod(
            accountId = subscription.accountId,
            paymentMethod = bankTransferPaymentMethod,
            paymentGateway = paymentGatewayToUse,
            billtoContact = billToContact,
          )
          _ <- SimpleEitherT(
            annotateFailableFuture(
              services.zuoraSoapService.createPaymentMethod(createPaymentMethod),
              s"create direct debit payment method using ${paymentGatewayToUse.gatewayName}",
            ),
          )
          freshAccount <- SimpleEitherT(
            annotateFailableFuture(
              services.zuoraSoapService.getAccount(subscription.accountId),
              s"get fresh account with id ${subscription.accountId}",
            ),
          )
          freshDefaultPaymentMethodOption <- SimpleEitherT(
            annotateFailableFuture(services.paymentService.getPaymentMethod(freshAccount.defaultPaymentMethodId), "get fresh default payment method"),
          )
          catalog <- SimpleEitherT.rightT(services.futureCatalog)
          productType = subscription.plan(catalog).productType(catalog)
          _ <- sendPaymentMethodChangedEmail(user.primaryEmailAddress, contact, DirectDebit, productType)
        } yield checkDirectDebitUpdateResult(freshDefaultPaymentMethodOption, bankAccountName, bankAccountNumber, bankSortCode)).run
          .map(_.toEither)
          .map {
            case Left(message) =>
              logger.error(scrub"Failed to update direct debit due to $message")
              InternalServerError("")
            case Right(result) => result
          }
      }
    }
}
