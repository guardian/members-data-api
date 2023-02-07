package services.zuora.rest

import models.subscription.Subscription.{AccountId, Name, RatePlanId, SubscriptionRatePlanChargeId}
import com.gu.salesforce.ContactId
import com.gu.zuora.rest.ZuoraResponse
import services.zuora.rest.ZuoraRestService.{
  AccountSummary,
  AccountsByCrmIdResponse,
  AccountsByCrmIdResponseRecord,
  ContactData,
  GetAccountsQueryResponse,
  GiftSubscriptionsFromIdentityIdRecord,
  ObjectAccount,
  PaymentMethodResponse,
}
import monitoring.CreateMetrics
import org.joda.time.LocalDate
import scalaz.\/

import scala.concurrent.{ExecutionContext, Future}

class ZuoraRestServiceWithMetrics(private val wrapped: ZuoraRestService, createMetrics: CreateMetrics)(ec: ExecutionContext)
    extends ZuoraRestService {
  val metrics = createMetrics.forService(wrapped.getClass)

  override def getAccount(accountId: AccountId): Future[String \/ AccountSummary] =
    metrics.measureDuration("getAccount")(wrapped.getAccount(accountId))(ec)

  override def getObjectAccount(accountId: AccountId): Future[String \/ ObjectAccount] =
    metrics.measureDuration("getObjectAccount")(wrapped.getObjectAccount(accountId))(ec)

  override def getAccounts(identityId: String): Future[String \/ GetAccountsQueryResponse] =
    metrics.measureDuration("getAccounts")(wrapped.getAccounts(identityId))(ec)

  override def getAccountByCrmId(crmId: String): Future[String \/ AccountsByCrmIdResponse] =
    metrics.measureDuration("getAccountByCrmId")(wrapped.getAccountByCrmId(crmId))(ec)

  override def getGiftSubscriptionRecordsFromIdentityId(identityId: String): Future[String \/ List[GiftSubscriptionsFromIdentityIdRecord]] =
    metrics.measureDuration("getGiftSubscriptionRecordsFromIdentityId")(wrapped.getGiftSubscriptionRecordsFromIdentityId(identityId))(ec)

  override def getPaymentMethod(paymentMethodId: String): Future[String \/ PaymentMethodResponse] =
    metrics.measureDuration("getPaymentMethod")(wrapped.getPaymentMethod(paymentMethodId))(ec)

  override def addEmail(accountId: AccountId, email: String): Future[String \/ Unit] =
    metrics.measureDuration("addEmail")(wrapped.addEmail(accountId, email))(ec)

  override def updateAccountContacts(record: AccountsByCrmIdResponseRecord, soldTo: Option[ContactData], billTo: Option[ContactData])(implicit
      ex: ExecutionContext,
  ): Future[String \/ ZuoraResponse] =
    metrics.measureDuration("updateAccountContacts")(wrapped.updateAccountContacts(record, soldTo, billTo))

  override def updateAccountIdentityId(accountId: AccountId, identityId: String)(implicit ex: ExecutionContext): Future[String \/ ZuoraResponse] =
    metrics.measureDuration("updateAccountIdentityId")(wrapped.updateAccountIdentityId(accountId, identityId))

  override def cloneContact(id: String): Future[String \/ String] =
    metrics.measureDuration("cloneContact")(wrapped.cloneContact(id))(ec)

  override def updateZuoraBySfContact(contactId: ContactId, soldTo: Option[ContactData], billTo: Option[ContactData]): Future[String \/ Unit] =
    metrics.measureDuration("updateZuoraBySfContact")(wrapped.updateZuoraBySfContact(contactId, soldTo, billTo))(ec)

  override def cancelSubscription(subscriptionName: Name, termEndDate: LocalDate, maybeChargedThroughDate: Option[LocalDate])(implicit
      ex: ExecutionContext,
  ): Future[String \/ Unit] =
    metrics.measureDuration("cancelSubscription")(wrapped.cancelSubscription(subscriptionName, termEndDate, maybeChargedThroughDate))

  override def updateCancellationReason(subscriptionName: Name, userCancellationReason: String): Future[String \/ Unit] =
    metrics.measureDuration("updateCancellationReason")(wrapped.updateCancellationReason(subscriptionName, userCancellationReason))(ec)

  override def disableAutoPay(accountId: AccountId): Future[String \/ Unit] =
    metrics.measureDuration("disableAutoPay")(wrapped.disableAutoPay(accountId))(ec)

  override def updateChargeAmount(
      subscriptionName: Name,
      ratePlanChargeId: SubscriptionRatePlanChargeId,
      ratePlanId: RatePlanId,
      amount: Double,
      reason: String,
      applyFromDate: LocalDate,
  )(implicit ex: ExecutionContext): Future[String \/ Unit] =
    metrics.measureDuration("updateChargeAmount")(
      wrapped.updateChargeAmount(subscriptionName, ratePlanChargeId, ratePlanId, amount, reason, applyFromDate),
    )

  override def getCancellationEffectiveDate(name: Name): Future[String \/ Option[String]] =
    metrics.measureDuration("getCancellationEffectiveDate")(wrapped.getCancellationEffectiveDate(name))(ec)
}
