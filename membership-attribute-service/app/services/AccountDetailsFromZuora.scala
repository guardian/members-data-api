package services

import com.gu.memsub.Product
import com.gu.memsub.Subscription.Name
import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{PaidChargeList, Subscription, SubscriptionPlan}
import com.gu.salesforce.{Contact, SimpleContactRepository}
import com.gu.services.model.PaymentDetails
import com.gu.stripe.StripeService
import com.gu.zuora.api.{PaymentGateway, RegionalStripeGateways}
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.PaymentMethodId
import controllers.AccountController
import controllers.AccountHelpers.{FilterByProductType, FilterBySubName, NoFilter, OptionalSubscriptionsFilter}
import controllers.PaymentDetailMapper.paymentDetailsForSub
import models.{AccountDetails, ContactAndSubscription, DeliveryAddress}
import monitoring.CreateMetrics
import scalaz.std.scalaFuture._
import scalaz.{EitherT, IList, ListT, \/}
import services.PaymentFailureAlerter.{accountHasMissedPayments, alertText, safeToAllowPaymentUpdate}
import utils.ListTEither.ListTEither
import utils.OptionEither.FutureEither
import utils.SimpleEitherT.SimpleEitherT
import utils.{ListTEither, SimpleEitherT}

import scala.concurrent.{ExecutionContext, Future}

class AccountDetailsFromZuora(createMetrics: CreateMetrics,
                              zuoraRestService: ZuoraRestService[Future],
                              contactRepository: SimpleContactRepository,
                              subscriptionService: SubscriptionService[Future],
                              stripeServicesByPaymentGateway: Map[PaymentGateway, StripeService],
                              ukStripeService: StripeService,
                              paymentService: PaymentService,
                             )(implicit executionContext: ExecutionContext) {
  private val metrics = createMetrics.forService(classOf[AccountController])

  def get(userId: String, filter: OptionalSubscriptionsFilter): SimpleEitherT[List[AccountDetails]] = {
    def getPaymentMethod(id: PaymentMethodId): Future[Either[String, ZuoraRestService.PaymentMethodResponse]] =
      zuoraRestService.getPaymentMethod(id.get).map(_.toEither)

    SimpleEitherT(metrics.measureDuration("accountDetailsFromZuora") {
      (for {
        contactAndSubscription <- ListTEither(
          allCurrentSubscriptions(userId, filter),
        )
        freeOrPaidSub = contactAndSubscription.subscription.plan.charges match {
          case _: PaidChargeList => Right(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Paid]])
          case _ => Left(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Free]])
        }
        detailsResultsTuple <- ListTEither.single(getAccountDetailsParallel(contactAndSubscription, freeOrPaidSub))
        (paymentDetails, accountSummary, effectiveCancellationDate) = detailsResultsTuple
        stripeService = accountSummary.billToContact.country
          .map(RegionalStripeGateways.getGatewayForCountry)
          .flatMap(stripeServicesByPaymentGateway.get)
          .getOrElse(ukStripeService)
        alertText <- ListTEither.singleRightT(alertText(accountSummary, contactAndSubscription.subscription, getPaymentMethod))
        isAutoRenew = contactAndSubscription.subscription.autoRenew
      } yield AccountDetails(
        contactId = contactAndSubscription.contact.salesforceContactId,
        regNumber = None,
        email = accountSummary.billToContact.email,
        deliveryAddress = Some(DeliveryAddress.fromContact(contactAndSubscription.contact)),
        subscription = contactAndSubscription.subscription,
        paymentDetails = paymentDetails,
        billingCountry = accountSummary.billToContact.country,
        stripePublicKey = stripeService.publicKey,
        accountHasMissedRecentPayments = freeOrPaidSub.isRight &&
          accountHasMissedPayments(contactAndSubscription.subscription.accountId, accountSummary.invoices, accountSummary.payments),
        safeToUpdatePaymentMethod = safeToAllowPaymentUpdate(contactAndSubscription.subscription.accountId, accountSummary.invoices),
        isAutoRenew = isAutoRenew,
        alertText = alertText,
        accountId = accountSummary.id.get,
        effectiveCancellationDate,
      )).toList.run
    })
  }

  private def allCurrentSubscriptions(
      userId: String,
      filter: OptionalSubscriptionsFilter,
  ): Future[\/[String, List[ContactAndSubscription]]] = {
    def nonGiftContactAndSubscriptionsFor(contact: Contact): Future[List[ContactAndSubscription]] = {
      subscriptionService
        .current[SubscriptionPlan.AnyPlan](contact)
        .map {
          _ map { subscription =>
            ContactAndSubscription(contact, subscription, isGiftRedemption = false)
          }
        }
    }

    def applyFilter(filter: OptionalSubscriptionsFilter, contactAndSubscriptions: List[ContactAndSubscription]) = {
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

    def subscriptionsFor(contact: Contact): EitherT[String, Future, List[ContactAndSubscription]] = {
      for {
        nonGiftContactAndSubscriptions <- EitherT.rightT(nonGiftContactAndSubscriptionsFor(contact))
        contactAndSubscriptions <- checkForGiftSubscription(
          userId,
          nonGiftContactAndSubscriptions,
          contact,
        )
        filtered = applyFilter(filter, contactAndSubscriptions)
      } yield filtered
    }

    SimpleEitherT(contactRepository.get(userId)).flatMap {
      case Some(contact) => subscriptionsFor(contact)
      case _ => EitherT.right[String, Future, List[ContactAndSubscription]](Nil)
    }.run
  }

  private def getAccountDetailsParallel(
      contactAndSubscription: ContactAndSubscription,
      freeOrPaidSub: Either[Subscription[SubscriptionPlan.Free], Subscription[SubscriptionPlan.Paid]],
  ): SimpleEitherT[(PaymentDetails, ZuoraRestService.AccountSummary, Option[String])] = {
    // Run all these api calls in parallel to improve response times
    val paymentDetailsFuture =
      paymentDetailsForSub(contactAndSubscription.isGiftRedemption, freeOrPaidSub, paymentService)
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

  private def checkForGiftSubscription(
      userId: String,
      nonGiftSubs: List[ContactAndSubscription],
      contact: Contact,
  ): EitherT[String, Future, List[ContactAndSubscription]] = {
    val giftSub = for {
      records <- ListT(EitherT(zuoraRestService.getGiftSubscriptionRecordsFromIdentityId(userId).map(_.map(IList(_)))))
      result <-
        if (records.isEmpty)
          ListTEither.fromList[Subscription[AnyPlan]](Nil)
        else
          reuseAlreadyFetchedSubscriptionIfAvailable(records, nonGiftSubs, subscriptionService)
    } yield result
    val fullList = giftSub
      .map(ContactAndSubscription(contact, _, isGiftRedemption = true))
    EitherT(fullList.run.map(_.toList).map(_ ++ nonGiftSubs).run)
  }

  def reuseAlreadyFetchedSubscriptionIfAvailable(
      giftRecords: List[ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord],
      nonGiftSubs: List[ContactAndSubscription],
      subscriptionService: SubscriptionService[Future],
  ): ListT[FutureEither, Subscription[AnyPlan]] = ListTEither.fromFutureList {
    val all = giftRecords.map { giftRecord =>
      val subscriptionName = Name(giftRecord.Name)
      // If the current user is both the gifter and the giftee we will have already retrieved their
      // subscription so we can reuse it and avoid a call to Zuora
      nonGiftSubs.find(_.subscription.name == subscriptionName) match {
        case Some(contactAndSubscription) => Future.successful(Some(contactAndSubscription.subscription))
        case _ =>
          subscriptionService
            .get[AnyPlan](subscriptionName, isActiveToday = false) // set isActiveToday to false so that we find digisub plans with a one time charge
      }
    }
    Future.sequence(all).map(_.flatten) // failures turn to None, and are logged, so just ignore them
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
      case _ => requestedProductType == product.name // fallback
    }
  }
}
