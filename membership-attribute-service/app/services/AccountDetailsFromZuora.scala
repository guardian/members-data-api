package services

import com.gu.memsub.Product
import com.gu.memsub.Subscription.Name
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.salesforce.Contact
import com.gu.services.model.PaymentDetails
import controllers.AccountController
import controllers.AccountHelpers.{FilterByProductType, FilterBySubName, NoFilter, OptionalSubscriptionsFilter}
import models.{AccountDetails, ContactAndSubscription, DeliveryAddress}
import monitoring.CreateMetrics
import scalaz.ListT
import scalaz.std.scalaFuture._
import services.DifferentiateSubscription.differentiateSubscription
import services.PaymentFailureAlerter.{accountHasMissedPayments, alertText, safeToAllowPaymentUpdate}
import services.salesforce.ContactRepository
import services.stripe.ChooseStripe
import services.subscription.SubscriptionService
import services.zuora.rest.ZuoraRestService
import services.zuora.rest.ZuoraRestService.PaymentMethodId
import utils.ListTEither.ListTEither
import utils.SimpleEitherT.SimpleEitherT
import utils.{ListTEither, SimpleEitherT}

import scala.concurrent.{ExecutionContext, Future}

class AccountDetailsFromZuora(
    createMetrics: CreateMetrics,
    zuoraRestService: ZuoraRestService,
    contactRepository: ContactRepository,
    subscriptionService: SubscriptionService,
    chooseStripe: ChooseStripe,
    paymentDetailsForSubscription: PaymentDetailsForSubscription,
)(implicit executionContext: ExecutionContext) {
  private val metrics = createMetrics.forService(classOf[AccountController])

  def fetch(userId: String, filter: OptionalSubscriptionsFilter): SimpleEitherT[List[AccountDetails]] = {
    metrics.measureDurationEither("accountDetailsFromZuora") {
      SimpleEitherT.fromListT(accountDetailsFromZuoraFor(userId, filter))
    }
  }

  private def accountDetailsFromZuoraFor(userId: String, filter: OptionalSubscriptionsFilter): ListT[SimpleEitherT, AccountDetails] = {
    for {
      contactAndSubscription <- allCurrentSubscriptions(userId, filter)
      isPaidSubscription = differentiateSubscription(contactAndSubscription).isRight
      detailsResultsTriple <- ListTEither.single(getAccountDetailsParallel(contactAndSubscription))
      (paymentDetails, accountSummary, effectiveCancellationDate) = detailsResultsTriple
      country = accountSummary.billToContact.country
      stripePublicKey = chooseStripe.publicKeyForCountry(country)
      alertText <- ListTEither.singleRightT(alertText(accountSummary, contactAndSubscription.subscription, getPaymentMethod))
      isAutoRenew = contactAndSubscription.subscription.autoRenew
    } yield {
      AccountDetails(
        contactId = contactAndSubscription.contact.salesforceContactId,
        regNumber = None,
        email = accountSummary.billToContact.email,
        deliveryAddress = Some(DeliveryAddress.fromContact(contactAndSubscription.contact)),
        subscription = contactAndSubscription.subscription,
        paymentDetails = paymentDetails,
        billingCountry = accountSummary.billToContact.country,
        stripePublicKey = stripePublicKey.key,
        accountHasMissedRecentPayments = isPaidSubscription &&
          accountHasMissedPayments(contactAndSubscription.subscription.accountId, accountSummary.invoices, accountSummary.payments),
        safeToUpdatePaymentMethod = safeToAllowPaymentUpdate(contactAndSubscription.subscription.accountId, accountSummary.invoices),
        isAutoRenew = isAutoRenew,
        alertText = alertText,
        accountId = accountSummary.id.get,
        effectiveCancellationDate,
      )
    }
  }

  private def getPaymentMethod(id: PaymentMethodId): Future[Either[String, ZuoraRestService.PaymentMethodResponse]] =
    zuoraRestService.getPaymentMethod(id.get).map(_.toEither)

  private def nonGiftContactAndSubscriptionsFor(contact: Contact): Future[List[ContactAndSubscription]] = {
    subscriptionService
      .current[SubscriptionPlan.AnyPlan](contact)
      .map(_.map(ContactAndSubscription(contact, _, isGiftRedemption = false)))
  }

  private def applyFilter(
      filter: OptionalSubscriptionsFilter,
      contactAndSubscriptions: List[ContactAndSubscription],
  ): List[ContactAndSubscription] = {
    filter match {
      case FilterBySubName(subscriptionName) =>
        contactAndSubscriptions.find(_.subscription.name == subscriptionName).toList
      case FilterByProductType(productType) =>
        contactAndSubscriptions.filter(contactAndSubscription =>
          productIsInstanceOfProductType(
            contactAndSubscription.subscription.plan.product,
            productType,
          ),
        )
      case NoFilter =>
        contactAndSubscriptions
    }
  }

  private def subscriptionsFor(userId: String, contact: Contact, filter: OptionalSubscriptionsFilter): SimpleEitherT[List[ContactAndSubscription]] = {
    for {
      nonGiftContactAndSubscriptions <- SimpleEitherT.rightT(nonGiftContactAndSubscriptionsFor(contact))
      contactAndSubscriptions <- checkForGiftSubscription(userId, nonGiftContactAndSubscriptions, contact)
      filtered = applyFilter(filter, contactAndSubscriptions)
    } yield filtered
  }

  private def allCurrentSubscriptions(
      userId: String,
      filter: OptionalSubscriptionsFilter,
  ): ListTEither[ContactAndSubscription] = {
    for {
      contact <- ListTEither.fromFutureOption(contactRepository.get(userId))
      subscription <- ListTEither.fromEitherT(subscriptionsFor(userId, contact, filter))
    } yield subscription
  }

  private def getAccountDetailsParallel(
      contactAndSubscription: ContactAndSubscription,
  ): SimpleEitherT[(PaymentDetails, ZuoraRestService.AccountSummary, Option[String])] = {
    metrics.measureDurationEither("getAccountDetailsParallel") {
      // Run all these api calls in parallel to improve response times
      val paymentDetailsFuture =
        paymentDetailsForSubscription(contactAndSubscription)
          .map(Right(_))
          .recover { case x =>
            Left(s"error retrieving payment details for subscription: freeOrPaidSub.name. Reason: $x")
          }

      val accountSummaryFuture =
        zuoraRestService
          .getAccount(contactAndSubscription.subscription.accountId)
          .map(_.toEither)
          .recover { case x =>
            Left(
              s"error receiving account summary for subscription: ${contactAndSubscription.subscription.name} " +
                s"with account id ${contactAndSubscription.subscription.accountId}. Reason: $x",
            )
          }

      val effectiveCancellationDateFuture =
        zuoraRestService
          .getCancellationEffectiveDate(contactAndSubscription.subscription.name)
          .map(_.toEither)
          .recover { case x =>
            Left(
              s"Failed to fetch effective cancellation date: ${contactAndSubscription.subscription.name} " +
                s"with account id ${contactAndSubscription.subscription.accountId}. Reason: $x",
            )
          }

      for {
        paymentDetails <- SimpleEitherT(paymentDetailsFuture)
        accountSummary <- SimpleEitherT(accountSummaryFuture)
        effectiveCancellationDate <- SimpleEitherT(effectiveCancellationDateFuture)
      } yield (paymentDetails, accountSummary, effectiveCancellationDate)
    }
  }

  private def checkForGiftSubscription(
      userId: String,
      nonGiftSubscription: List[ContactAndSubscription],
      contact: Contact,
  ): SimpleEitherT[List[ContactAndSubscription]] = {
    metrics.measureDurationEither("checkForGiftSubscription") {
      for {
        records <- SimpleEitherT(zuoraRestService.getGiftSubscriptionRecordsFromIdentityId(userId))
        reused <- reuseAlreadyFetchedSubscriptionIfAvailable(records, nonGiftSubscription)
        contactAndSubscriptions = reused.map(ContactAndSubscription(contact, _, isGiftRedemption = true))
      } yield contactAndSubscriptions ++ nonGiftSubscription
    }
  }

  private def reuseAlreadyFetchedSubscriptionIfAvailable(
      giftRecords: List[ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord],
      nonGiftSubs: List[ContactAndSubscription],
  ): SimpleEitherT[List[Subscription[AnyPlan]]] = {
    val all = giftRecords.map { giftRecord =>
      val subscriptionName = Name(giftRecord.Name)
      // If the current user is both the gifter and the giftee we will have already retrieved their
      // subscription so we can reuse it and avoid a call to Zuora
      val matchingSubscription: Option[ContactAndSubscription] = nonGiftSubs.find(_.subscription.name == subscriptionName)
      matchingSubscription
        .map(contactAndSubscription => Future.successful(Some(contactAndSubscription.subscription)))
        .getOrElse(subscriptionService.get[AnyPlan](subscriptionName, isActiveToday = false))
    }
    val result: Future[List[Subscription[AnyPlan]]] = Future.sequence(all).map(_.flatten)
    SimpleEitherT.rightT(result) // failures turn to None, and are logged, so just ignore them
  }

  private def productIsInstanceOfProductType(product: Product, requestedProductType: String) = {
    val requestedProductTypeIsContentSubscription: Boolean = requestedProductType == "ContentSubscription"
    product match {
      // this ordering prevents Weekly subs from coming back when Paper is requested (which is different from the type hierarchy where Weekly extends Paper)
      case _: Product.Weekly => requestedProductType == "Weekly" || requestedProductTypeIsContentSubscription
      case _: Product.Voucher => requestedProductType == "Voucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.DigitalVoucher =>
        requestedProductType == "DigitalVoucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Delivery =>
        requestedProductType == "HomeDelivery" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Contribution => requestedProductType == "Contribution"
      case _: Product.Membership => requestedProductType == "Membership"
      case _: Product.ZDigipack => requestedProductType == "Digipack" || requestedProductTypeIsContentSubscription
      case _: Product.ZSupporterPlus => requestedProductType == "SupporterPlus" || requestedProductTypeIsContentSubscription
      case _ => requestedProductType == product.name // fallback
    }
  }
}
