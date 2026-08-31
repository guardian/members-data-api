package services.zuora.rest

import com.gu.i18n.{Country, Currency, Title}
import com.gu.memsub.Subscription.{AccountId, AccountNumber, SubscriptionNumber, RatePlanId, SubscriptionRatePlanChargeId}
import com.gu.memsub.subsv2.reads.CommonReads._
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.salesforce.ContactId
import com.gu.zuora.ZuoraLookup
import com.gu.zuora.api.PaymentGateway
import com.gu.zuora.rest.ZuoraResponse
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import scalaz.\/
import services.zuora.rest.ZuoraRestService.{
  AccountSummary,
  AccountsByCrmIdResponse,
  AccountsByCrmIdResponseRecord,
  BillingPreviewInvoiceItem,
  ContactData,
  GetAccountsQueryResponse,
  GiftSubscriptionsFromIdentityIdRecord,
  ObjectAccount,
  PaymentMethodResponse,
}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.Try

object ZuoraRestService {

  import com.gu.memsub.subsv2.reads.CommonReads.localWrites
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._
  import play.api.libs.json._

  implicit class MapOps(in: Map[String, Option[String]]) {
    def flattenWithDefault(defaultValue: String) = in.collect {
      case (key, Some(value)) => key -> value
      case (key, None) => key -> defaultValue
    }
  }

  def jsStringOrNull(value: Option[String]) = value.map(JsString(_)).getOrElse(JsNull)
  def isoDateStringAsDateTime(dateString: String): DateTime = ISODateTimeFormat.dateTimeParser().parseDateTime(dateString)

  /** Parse an ISO datetime into the JsResult error channel: a malformed value becomes a JsError rather than a thrown exception that would escape the
    * Reads and crash parsing of the whole payload.
    */
  def isoDateStringAsDateTimeResult(dateString: String): JsResult[DateTime] =
    Try(isoDateStringAsDateTime(dateString)).fold(
      error => JsError(s"could not parse '$dateString' as an ISO 8601 datetime: ${error.toString}"),
      JsSuccess(_),
    )

  case class AddressData(
      address1: Option[String],
      address2: Option[String],
      city: Option[String],
      state: Option[String],
      zipCode: Option[String],
      country: String,
  ) {
    def asJsObject: JsObject = Json.obj(
      "address1" -> jsStringOrNull(address1),
      "city" -> jsStringOrNull(city),
      "country" -> JsString(country),
      "address2" -> jsStringOrNull(address2),
      "state" -> jsStringOrNull(state),
      "zipCode" -> jsStringOrNull(zipCode),
    )

  }

  // these classes looks similar to what we use in RestQuery maybe we can remove that duplication
  case class ContactData(
      title: Option[String],
      firstName: String,
      lastName: String,
      specialDeliveryInstructions: Option[String],
      workEmail: Option[String],
      companyName: Option[String],
      address: AddressData,
  ) {
    def asJsObject: JsObject = Json.obj(
      "firstName" -> JsString(firstName),
      "lastName" -> JsString(lastName),
      "Title__c" -> jsStringOrNull(title),
      "SpecialDeliveryInstructions__c" -> jsStringOrNull(specialDeliveryInstructions),
      "workEmail" -> jsStringOrNull(workEmail),
      "Company_Name__c" -> jsStringOrNull(companyName),
    ) ++ address.asJsObject

  }

  case class UpdateContactsCommand(billTo: Option[ContactData], soldTo: Option[ContactData])

  implicit val updateContactsWrites = new Writes[UpdateContactsCommand] {
    override def writes(command: UpdateContactsCommand): JsValue = {
      val billtoJson = command.billTo.map(billto => Json.obj("billToContact" -> billto.asJsObject))
      val soldToJson = command.soldTo.map(soldto => Json.obj("soldToContact" -> soldto.asJsObject))

      val maybeDisableEmailInvoices = command.billTo.flatMap { billTocontact =>
        if (billTocontact.workEmail.isEmpty) {
          Some(Json.obj("invoiceDeliveryPrefsEmail" -> JsBoolean(false)))
        } else {
          None
        }
      }

      val jsonParts = List(billtoJson, soldToJson, maybeDisableEmailInvoices).flatten
      jsonParts.foldRight(Json.obj())(_ ++ _)
    }
  }

  case class UpdateAccountIdentityIdCommand(identityId: String)

  implicit val updateAccountWithIdentityIdWrites = new Writes[UpdateAccountIdentityIdCommand] {
    override def writes(c: UpdateAccountIdentityIdCommand): JsValue = Json.obj(
      "IdentityId__c" -> c.identityId,
    )
  }

  case class CancellationOrderRequest(
      orderDate: LocalDate,
      existingAccountId: String,
      subscriptions: List[OrderSubscription],
      processingOptions: OrderProcessingOptions,
  )

  object CancellationOrderRequest {
    def forSubscription(
        accountId: AccountId,
        subscriptionNumber: SubscriptionNumber,
        orderDate: LocalDate,
        cancellationEffectiveDate: LocalDate,
        needsTermRenewal: Boolean,
    ): CancellationOrderRequest = {
      val renewSubscriptionAction =
        if (needsTermRenewal) List(RenewSubscriptionOrderAction(TriggerDates.allOn(orderDate)))
        else Nil

      CancellationOrderRequest(
        orderDate = orderDate,
        existingAccountId = accountId.get,
        subscriptions = List(
          OrderSubscription(
            subscriptionNumber = subscriptionNumber.getNumber,
            orderActions = renewSubscriptionAction :+ CancelSubscriptionOrderAction(
              triggerDates = TriggerDates(cancellationEffectiveDate),
              cancelSubscription = Cancellation(CancellationPolicy.SpecificDate, cancellationEffectiveDate),
            ),
          ),
        ),
        processingOptions = OrderProcessingOptions(runBilling = false, collectPayment = false),
      )
    }
  }

  case class OrderSubscription(subscriptionNumber: String, orderActions: List[SubscriptionOrderAction])

  sealed trait SubscriptionOrderAction
  case class RenewSubscriptionOrderAction(triggerDates: TriggerDates) extends SubscriptionOrderAction
  case class CancelSubscriptionOrderAction(triggerDates: TriggerDates, cancelSubscription: Cancellation) extends SubscriptionOrderAction

  case class TriggerDates(
      contractEffective: LocalDate,
      serviceActivation: Option[LocalDate] = None,
      customerAcceptance: Option[LocalDate] = None,
  )

  object TriggerDates {
    def allOn(date: LocalDate): TriggerDates = TriggerDates(date, Some(date), Some(date))

    implicit val writes: Writes[TriggerDates] = Writes { dates =>
      Json.toJson(
        List(
          Some(Json.obj("name" -> "ContractEffective", "triggerDate" -> dates.contractEffective)),
          dates.serviceActivation.map(date => Json.obj("name" -> "ServiceActivation", "triggerDate" -> date)),
          dates.customerAcceptance.map(date => Json.obj("name" -> "CustomerAcceptance", "triggerDate" -> date)),
        ).flatten,
      )
    }
  }

  sealed trait CancellationPolicy {
    def value: String
  }

  object CancellationPolicy {
    case object SpecificDate extends CancellationPolicy {
      override val value = "SpecificDate"
    }

    implicit val cancellationPolicyWrites: Writes[CancellationPolicy] = Writes(policy => JsString(policy.value))
  }

  case class Cancellation(cancellationPolicy: CancellationPolicy, cancellationEffectiveDate: LocalDate)
  case class OrderProcessingOptions(runBilling: Boolean, collectPayment: Boolean)

  implicit val cancellationWrites: Writes[Cancellation] = Json.writes[Cancellation]
  implicit val orderProcessingOptionsWrites: Writes[OrderProcessingOptions] = Json.writes[OrderProcessingOptions]
  implicit val subscriptionOrderActionWrites: Writes[SubscriptionOrderAction] = new Writes[SubscriptionOrderAction] {
    override def writes(action: SubscriptionOrderAction): JsValue = action match {
      case RenewSubscriptionOrderAction(triggerDates) =>
        Json.obj("type" -> "RenewSubscription", "triggerDates" -> triggerDates)
      case CancelSubscriptionOrderAction(triggerDates, cancellation) =>
        Json.obj("type" -> "CancelSubscription", "triggerDates" -> triggerDates, "cancelSubscription" -> cancellation)
    }
  }
  implicit val orderSubscriptionWrites: Writes[OrderSubscription] = Json.writes[OrderSubscription]
  implicit val cancellationOrderRequestWrites: Writes[CancellationOrderRequest] = Json.writes[CancellationOrderRequest]

  case class UpdateCancellationSubscriptionCommand(cancellationReason: String, userCancellationReason: String)

  implicit val updateCancellationSubscriptionCommand = new Writes[UpdateCancellationSubscriptionCommand] {
    override def writes(command: UpdateCancellationSubscriptionCommand): JsValue = {
      Json.obj(
        "CancellationReason__c" -> command.cancellationReason,
        "UserCancellationReason__c" -> command.userCancellationReason,
      )
    }
  }

  case class DisableAutoPayCommand()

  implicit val disableAutoPayCommand = new Writes[DisableAutoPayCommand] {
    override def writes(command: DisableAutoPayCommand): JsValue = {
      Json.obj(
        "autoPay" -> false,
      )
    }
  }

  case class UpdateAccountCommand(email: String)

  implicit val updateAccountCommandWrites = new Writes[UpdateAccountCommand] {
    override def writes(command: UpdateAccountCommand): JsValue = {
      Json.obj(
        "billToContact" ->
          Json.obj(
            "workEmail" -> command.email,
          ),
      )
    }
  }

  case class UpdateChargeCommand(
      price: Double,
      ratePlanChargeId: SubscriptionRatePlanChargeId,
      ratePlanId: RatePlanId,
      applyFromDate: LocalDate,
      note: String,
  )

  implicit val updateChargeCommandWrites = new Writes[UpdateChargeCommand] {
    override def writes(command: UpdateChargeCommand): JsValue = {
      Json.obj(
        "notes" -> command.note,
        "update" ->
          Json.arr(
            Json.obj(
              "chargeUpdateDetails" ->
                Json.arr(
                  Json.obj(
                    "price" -> command.price,
                    "ratePlanChargeId" -> command.ratePlanChargeId.get,
                  ),
                ),
              "contractEffectiveDate" -> command.applyFromDate,
              "customerAcceptanceDate" -> command.applyFromDate,
              "serviceActivationDate" -> command.applyFromDate,
              "ratePlanId" -> command.ratePlanId.get,
            ),
          ),
      )
    }
  }

  case class RestQuery(queryString: String)
  implicit val restQueryWrites = Json.writes[RestQuery]

  /** Billing Preview, the REST replacement for the SOAP amend-with-preview hack. assumeRenewal projects the next charge for subs already billed for
    * their current term, so the old "evergreen" trick is no longer needed. See
    * https://www.zuora.com/developer/api-references/api/operation/POST_BillingPreview
    */
  case class BillingPreviewRequest(accountId: String, targetDate: LocalDate, assumeRenewal: String = "Autorenew")
  implicit val billingPreviewRequestWrites = Json.writes[BillingPreviewRequest]

  implicit val subscriptionNumberReads: Reads[SubscriptionNumber] = Json.valueReads[SubscriptionNumber]
  case class BillingPreviewInvoiceItem(
      subscriptionNumber: SubscriptionNumber,
      chargeAmount: Double,
      taxAmount: Double,
      serviceStartDate: String,
      serviceEndDate: String,
      chargeName: String,
  )
  implicit val billingPreviewInvoiceItemReads = Json.reads[BillingPreviewInvoiceItem]

  case class BillingPreviewResponse(invoiceItems: List[BillingPreviewInvoiceItem])
  implicit val billingPreviewResponseReads = Json.reads[BillingPreviewResponse]

  case class SalesforceContactId(get: String) extends AnyVal

  case class AccountSummary(
      id: AccountId,
      accountNumber: AccountNumber,
      identityId: Option[String],
      billToContact: BillToContact,
      soldToContact: SoldToContact,
      invoices: List[Invoice],
      payments: List[Payment],
      currency: Option[Currency],
      balance: Double,
      defaultPaymentMethod: Option[DefaultPaymentMethod],
      sfContactId: SalesforceContactId,
  )
  case class ObjectAccount(
      id: AccountId,
      autoPay: Option[Boolean],
      defaultPaymentMethodId: Option[PaymentMethodId],
      currency: Option[Currency],
  )
  case class BillToContact(
      email: Option[String],
      country: Option[Country],
  )
  case class SoldToContact(
      title: Option[Title],
      firstName: Option[String],
      lastName: String,
      email: Option[String],
      address1: Option[String],
      address2: Option[String],
      city: Option[String],
      postCode: Option[String],
      state: Option[String],
      country: Option[Country],
  )

  case class InvoiceId(get: String) extends AnyVal
  case class Invoice(
      id: InvoiceId,
      invoiceNumber: String,
      invoiceDate: DateTime,
      dueDate: DateTime,
      amount: Double,
      balance: Double,
      status: String,
  )

  case class PaidInvoice(invoiceNumber: String, appliedPaymentAmount: Double)

  case class Payment(
      status: String,
      paidInvoices: List[PaidInvoice],
  )

  case class PaymentMethodId(get: String) extends AnyVal
  case class DefaultPaymentMethod(id: PaymentMethodId)

  case class AccountObject(
      Id: AccountId,
      Balance: Double = 0,
      Currency: Option[Currency],
      DefaultPaymentMethodId: Option[PaymentMethodId] = None,
      PaymentGateway: Option[PaymentGateway] = None,
      LastInvoiceDate: Option[DateTime] = None,
  )

  case class GetAccountsQueryResponse(
      records: List[AccountObject],
      size: Int,
  )

  case class AccountsByCrmIdResponseRecord(Id: AccountId, SoldToId: Option[String], BillToId: Option[String], sfContactId__c: Option[String])

  case class AccountsByCrmIdResponse(
      records: List[AccountsByCrmIdResponseRecord],
      size: Int,
  )
  object AccountsByCrmIdResponseRecord {
    implicit val reads: Reads[AccountsByCrmIdResponseRecord] = Json.reads[AccountsByCrmIdResponseRecord]
  }
  object AccountsByCrmIdResponse {
    implicit val reads: Reads[AccountsByCrmIdResponse] = Json.reads[AccountsByCrmIdResponse]
  }

  case class GiftSubscriptionsFromIdentityIdRecord(Name: String, Id: String, TermEndDate: LocalDate)

  case class GiftSubscriptionsFromIdentityIdResponse(
      records: List[GiftSubscriptionsFromIdentityIdRecord],
      size: Int,
  )
  object GiftSubscriptionsFromIdentityIdRecord {
    implicit val reads: Reads[GiftSubscriptionsFromIdentityIdRecord] = (
      (JsPath \ "Name").read[String] and
        (JsPath \ "Id").read[String] and
        (JsPath \ "TermEndDate").read[LocalDate]
    )(GiftSubscriptionsFromIdentityIdRecord.apply _)
  }
  object GiftSubscriptionsFromIdentityIdResponse {
    implicit val reads: Reads[GiftSubscriptionsFromIdentityIdResponse] = Json.reads[GiftSubscriptionsFromIdentityIdResponse]
  }

  /** The type-specific part of a payment method. Modelling it as a discriminated union (rather than one flat record where every field is optional)
    * keeps the fields that only exist for a given type together, and lets required fields — e.g. a PayPal email — be non-optional.
    */
  sealed trait PaymentMethodDetails
  object PaymentMethodDetails {
    case class Card(
        number: Option[String],
        expirationMonth: Option[Int],
        expirationYear: Option[Int],
        cardType: Option[String],
        isReferenceTransaction: Boolean,
    ) extends PaymentMethodDetails
    case class BankTransfer(
        mandateId: Option[String],
        transferType: Option[String],
        accountName: Option[String],
        accountNumberMask: Option[String],
        bankCode: Option[String],
    ) extends PaymentMethodDetails
    case class PayPal(email: String) extends PaymentMethodDetails
  }

  case class PaymentMethodResponse(
      paymentMethodType: String,
      numConsecutiveFailures: Option[Int],
      lastTransactionDateTime: Option[DateTime],
      paymentMethodStatus: Option[String],
      details: PaymentMethodDetails,
  )

  private def paymentMethodDetailsReads(paymentMethodType: String, json: JsValue): JsResult[PaymentMethodDetails] =
    paymentMethodType match {
      case "CreditCard" | "CreditCardReferenceTransaction" =>
        for {
          maskNumber <- (json \ "CreditCardMaskNumber").validateOpt[String]
          expirationMonth <- (json \ "CreditCardExpirationMonth").validateOpt[Int]
          expirationYear <- (json \ "CreditCardExpirationYear").validateOpt[Int]
          cardType <- (json \ "CreditCardType").validateOpt[String]
        } yield PaymentMethodDetails.Card(
          number = maskNumber.map(_.takeRight(4)),
          expirationMonth = expirationMonth,
          expirationYear = expirationYear,
          cardType = cardType,
          isReferenceTransaction = paymentMethodType == "CreditCardReferenceTransaction",
        )
      case "BankTransfer" =>
        for {
          mandateId <- (json \ "MandateID").validateOpt[String]
          transferType <- (json \ "BankTransferType").validateOpt[String]
          accountName <- (json \ "BankTransferAccountName").validateOpt[String]
          accountNumberMask <- (json \ "BankTransferAccountNumberMask").validateOpt[String]
          bankCode <- (json \ "BankCode").validateOpt[String]
        } yield PaymentMethodDetails.BankTransfer(mandateId, transferType, accountName, accountNumberMask, bankCode)
      case "PayPal" =>
        (json \ "PaypalEmail").validate[String].map(PaymentMethodDetails.PayPal)
      case other =>
        // Fail loudly on a payment method type we don't model, rather than silently returning no details: downstream that
        // reads as a missing payment method, which is misleading. A JsError names the actual type and gives a useful stack trace.
        JsError(s"unknown payment method type: $other")
    }

  implicit val paymentMethodReads: Reads[PaymentMethodResponse] = Reads { json =>
    for {
      paymentMethodType <- (json \ "Type").validate[String]
      numConsecutiveFailures <- (json \ "NumConsecutiveFailures").validateOpt[Int]
      lastTransactionDateTime <- (json \ "LastTransactionDateTime").validateOpt[String].flatMap {
        case Some(dateString) => isoDateStringAsDateTimeResult(dateString).map(Some(_))
        case None => JsSuccess(None)
      }
      paymentMethodStatus <- (json \ "PaymentMethodStatus").validateOpt[String]
      details <- paymentMethodDetailsReads(paymentMethodType, json)
    } yield PaymentMethodResponse(
      paymentMethodType = paymentMethodType,
      numConsecutiveFailures = numConsecutiveFailures,
      lastTransactionDateTime = lastTransactionDateTime,
      paymentMethodStatus = paymentMethodStatus,
      details = details,
    )
  }

  implicit val paymentGatewayReads: Reads[Option[PaymentGateway]] =
    __.read[String].map(PaymentGateway.getByName)

  implicit val currencyReads: Reads[Option[Currency]] =
    __.read[String].map(Currency.fromString)

  implicit val billToContactReads: Reads[BillToContact] = (
    (JsPath \ "workEmail").readNullable[String].map(_.filter(_.nonEmpty)) and
      (JsPath \ "country").read[String].map(ZuoraLookup.country)
  )(BillToContact.apply _)

  implicit val soldToContactReads: Reads[SoldToContact] =
    (
      (JsPath \ "Title__c").readNullable[String].map(_.flatMap(Title.fromString)) and
        (JsPath \ "firstName").readNullable[String] and
        (JsPath \ "lastName").read[String] and
        (JsPath \ "workEmail").readNullable[String] and
        (JsPath \ "address1").readNullable[String] and
        (JsPath \ "address2").readNullable[String] and
        (JsPath \ "city").readNullable[String] and
        (JsPath \ "zipCode").readNullable[String] and
        (JsPath \ "state").readNullable[String] and
        (JsPath \ "country").read[String].map(ZuoraLookup.country)
    )(SoldToContact.apply _)

  implicit val invoiceReads: Reads[Invoice] =
    (
      (JsPath \ "id").read[String].map(InvoiceId.apply) and
        (JsPath \ "invoiceNumber").read[String] and
        (JsPath \ "invoiceDate").read[String].map(isoDateStringAsDateTime) and
        (JsPath \ "dueDate").read[String].map(isoDateStringAsDateTime) and
        (JsPath \ "amount").read[Double] and
        (JsPath \ "balance").read[Double] and
        (JsPath \ "status").read[String]
    )(Invoice.apply _)

  implicit val paidInvoiceReads: Reads[PaidInvoice] = (
    (JsPath \ "invoiceNumber").read[String] and
      (JsPath \ "appliedPaymentAmount").read[Double]
  )(PaidInvoice.apply _)

  implicit val paymentReads: Reads[Payment] = (
    (JsPath \ "status").read[String] and
      (JsPath \ "paidInvoices").read[List[PaidInvoice]]
  )(Payment.apply _)

  implicit val paymentMethodIdReads: Reads[PaymentMethodId] = JsPath.read[String].map(PaymentMethodId.apply)
  implicit val defaultPaymentMethodReads: Reads[DefaultPaymentMethod] = Json.reads[DefaultPaymentMethod]

  implicit val accountSummaryReads: Reads[AccountSummary] = (
    (__ \ "basicInfo" \ "id").read[String].map(AccountId.apply) and
      (__ \ "basicInfo" \ "accountNumber").read[String].map(AccountNumber.apply) and
      (__ \ "basicInfo" \ "IdentityId__c").readNullable[String] and
      (__ \ "billToContact").read[BillToContact] and
      (__ \ "soldToContact").read[SoldToContact] and
      (__ \ "invoices").read[List[Invoice]] and
      (__ \ "payments").read[List[Payment]] and
      (__ \ "basicInfo" \ "currency").read[Option[Currency]] and
      (__ \ "basicInfo" \ "balance").read[Double] and
      (__ \ "basicInfo" \ "defaultPaymentMethod").readNullable[DefaultPaymentMethod] and
      (__ \ "basicInfo" \ "sfContactId__c").read[String].map(SalesforceContactId.apply)
  )(AccountSummary.apply _)

  implicit val objectAccountReads: Reads[ObjectAccount] = (
    (__ \ "Id").read[String].map(AccountId.apply) and
      (__ \ "AutoPay").readNullable[Boolean] and
      (__ \ "DefaultPaymentMethodId").readNullable[PaymentMethodId] and
      (__ \ "Currency").read[Option[Currency]]
  )(ObjectAccount.apply _)

  implicit val nameReads: Reads[AccountId] = JsPath.read[String].map(AccountId.apply)
  implicit val accountObjectReads: Reads[AccountObject] = (
    (JsPath \ "Id").read[AccountId] and
      (JsPath \ "Balance").read[Double] and
      (JsPath \ "Currency").read[Option[Currency]] and
      (JsPath \ "DefaultPaymentMethodId").readNullable[PaymentMethodId] and
      (JsPath \ "PaymentGateway").readWithDefault[Option[PaymentGateway]](None) and
      (JsPath \ "LastInvoiceDate").readNullable[String].map(_.map(isoDateStringAsDateTime))
  )(AccountObject.apply _)
  implicit val queryResponseReads: Reads[GetAccountsQueryResponse] = Json.reads[GetAccountsQueryResponse]

  case class Amendment(effectiveDate: Option[String], `type`: Option[String])
  implicit val amendment: Reads[Amendment] = Json.reads[Amendment]
  case class CancelledSubscription(subscriptionEndDate: String, status: String)
  implicit val cancelledSubscription: Reads[CancelledSubscription] = Json.reads[CancelledSubscription]

}

trait ZuoraRestService {
  def getAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[String \/ AccountSummary]

  def getObjectAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[String \/ ObjectAccount]

  def getGiftSubscriptionRecordsFromIdentityId(identityId: String)(implicit
      logPrefix: LogPrefix,
  ): Future[String \/ List[GiftSubscriptionsFromIdentityIdRecord]]

  def getPaymentMethod(paymentMethodId: String)(implicit logPrefix: LogPrefix): Future[String \/ PaymentMethodResponse]

  def getBillingPreview(accountId: AccountId, targetDate: LocalDate)(implicit
      logPrefix: LogPrefix,
  ): Future[String \/ List[BillingPreviewInvoiceItem]]

  def cancelSubscription(
      subscriptionNumber: SubscriptionNumber,
      accountId: AccountId,
      termEndDate: LocalDate,
      maybeChargedThroughDate: Option[
        LocalDate,
      ], // FIXME: Optionality should probably be removed and semantics changed to cancellationEffectiveDate (see comments bellow)
  )(implicit ex: ExecutionContext, logPrefix: LogPrefix): Future[String \/ Unit]

  def updateCancellationReason(subscriptionNumber: SubscriptionNumber, userCancellationReason: String)(implicit
      logPrefix: LogPrefix,
  ): Future[String \/ Unit]

  def disableAutoPay(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[String \/ Unit]

  def updateChargeAmount(
      subscriptionNumber: SubscriptionNumber,
      ratePlanChargeId: SubscriptionRatePlanChargeId,
      ratePlanId: RatePlanId,
      amount: Double,
      reason: String,
      applyFromDate: LocalDate,
  )(implicit ex: ExecutionContext, logPrefix: LogPrefix): Future[\/[String, Unit]]

  def getCancellationEffectiveDate(subscriptionNumber: SubscriptionNumber)(implicit logPrefix: LogPrefix): Future[String \/ Option[String]]
}
