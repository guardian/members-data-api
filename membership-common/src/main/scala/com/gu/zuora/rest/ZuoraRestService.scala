package com.gu.zuora.rest

import com.gu.memsub.Subscription._
import com.gu.zuora.ZuoraLookup
import com.gu.zuora.rest.ZuoraRestService._
import com.gu.i18n.{Country, Currency, Title}
import com.gu.memsub.subsv2.reads.CommonReads._
import com.gu.salesforce.ContactId
import com.gu.monitoring.SafeLogger
import com.gu.zuora.api.PaymentGateway
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scalaz.std.list._
import scalaz.{-\/, EitherT, Monad, \/, \/-}

object ZuoraRestService {

  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._
  import play.api.libs.json._
  import com.gu.memsub.subsv2.reads.CommonReads.localWrites

  implicit class MapOps(in: Map[String, Option[String]]) {
    def flattenWithDefault(defaultValue: String) = in.collect {
      case (key, Some(value)) => key -> value
      case (key, None) => key -> defaultValue
    }
  }

  def jsStringOrNull(value: Option[String]) = value.map(JsString(_)).getOrElse(JsNull)
  def isoDateStringAsDateTime(dateString: String): DateTime = ISODateTimeFormat.dateTimeParser().parseDateTime(dateString)

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

  case class CancelSubscriptionCommand(cancellationEffectiveDate: LocalDate)

  implicit val cancelSubscriptionCommandWrites = new Writes[CancelSubscriptionCommand] {
    override def writes(command: CancelSubscriptionCommand): JsValue =
      Json.obj(
        "cancellationPolicy" -> "SpecificDate",
        "cancellationEffectiveDate" -> command.cancellationEffectiveDate,
        "invoiceCollect" -> false,
      )
  }

  case class RenewSubscriptionCommand()

  implicit val renewSubscriptionCommandWrites = new Writes[RenewSubscriptionCommand] {
    override def writes(command: RenewSubscriptionCommand): JsValue =
      Json.obj(
        "invoiceCollect" -> false,
      )
  }

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

  case class PaymentMethodResponse(numConsecutiveFailures: Int, paymentMethodType: String, lastTransactionDateTime: DateTime)

  implicit val paymentMethodReads: Reads[PaymentMethodResponse] = (
    (JsPath \ "NumConsecutiveFailures").read[Int] and
      (JsPath \ "Type").read[String] and
      (JsPath \ "LastTransactionDateTime").read[String].map(isoDateStringAsDateTime)
  )(PaymentMethodResponse.apply _)

  implicit val paymentGatewayReads: Reads[Option[PaymentGateway]] =
    __.read[String].map(PaymentGateway.getByName)

  implicit val currencyReads: Reads[Option[Currency]] =
    __.read[String].map(Currency.fromString)

  implicit val billToContactReads: Reads[BillToContact] = (
    (JsPath \ "workEmail").readNullable[String].filter(_ != "") and
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

class ZuoraRestService[M[_]: Monad](implicit simpleRest: SimpleClient[M]) {

  def getAccount(accountId: AccountId): M[String \/ AccountSummary] = {
    simpleRest.get[AccountSummary](s"accounts/${accountId.get}/summary") // TODO error handling
  }

  def getObjectAccount(accountId: AccountId): M[String \/ ObjectAccount] = {
    simpleRest.get[ObjectAccount](s"object/account/${accountId.get}")
  }

  def getAccounts(identityId: String): M[String \/ GetAccountsQueryResponse] = {
    val queryString =
      s"select Id, Balance, Currency, DefaultPaymentMethodId, PaymentGateway, LastInvoiceDate from account where IdentityId__c = '$identityId' and Status = 'Active'"
    simpleRest.post[RestQuery, GetAccountsQueryResponse]("action/query", RestQuery(queryString))
  }

  def getAccountByCrmId(crmId: String): M[String \/ AccountsByCrmIdResponse] = {
    val queryString = s"select Id, BillToId, SoldToId, sfContactId__c  from Account where CrmId = '$crmId'"
    simpleRest.post[RestQuery, AccountsByCrmIdResponse]("action/query", RestQuery(queryString))
  }

  def getGiftSubscriptionRecordsFromIdentityId(identityId: String): M[String \/ List[GiftSubscriptionsFromIdentityIdRecord]] = {
    val today = LocalDate.now().toString("yyyy-MM-dd")
    val queryString =
      s"select name, id, termEndDate from subscription where GifteeIdentityId__c = '${identityId}' and status = 'Active' and termEndDate >= '$today'"
    val response = simpleRest.post[RestQuery, GiftSubscriptionsFromIdentityIdResponse]("action/query", RestQuery(queryString))
    EitherT(response).map(_.records).run
  }

  def getPaymentMethod(paymentMethodId: String): M[String \/ PaymentMethodResponse] =
    simpleRest.get[PaymentMethodResponse](s"object/payment-method/$paymentMethodId")

  def addEmail(accountId: AccountId, email: String): M[String \/ Unit] = {

    val future = implicitly[Monad[M]]

    val restResponse = for {
      account <- EitherT(getAccount(accountId))
      _ <- EitherT(
        future.point(
          if (account.billToContact.email.isEmpty) \/.r[String](())
          else \/.l[Unit](s"email is already set in zuora to ${account.billToContact.email}"),
        ),
      )
      restResponse <- EitherT(simpleRest.put[UpdateAccountCommand, ZuoraResponse](s"accounts/${accountId.get}", UpdateAccountCommand(email = email)))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run

  }

  private def unsuccessfulResponseToLeft(restResponse: EitherT[String, M, ZuoraResponse]): EitherT[String, M, ZuoraResponse] = {
    val futureMonad = implicitly[Monad[M]]

    val validated = futureMonad.map(restResponse.run) {
      case \/-(zuoraResponse) =>
        if (zuoraResponse.success) \/.r[String](zuoraResponse)
        else \/.l[ZuoraResponse](zuoraResponse.error.getOrElse("Zuora returned with success = false"))
      case -\/(e) => \/.l[ZuoraResponse](e)
    }

    EitherT(validated)
  }

  def updateAccountContacts(record: AccountsByCrmIdResponseRecord, soldTo: Option[ContactData], billTo: Option[ContactData])(implicit
      ex: ExecutionContext,
  ): M[\/[String, ZuoraResponse]] = {
    val futureMonad = implicitly[Monad[M]]

    (for {
      updated <- EitherT(splitContactsIfNecessary(record, soldTo))
      updateResponse <- EitherT(
        simpleRest.put[UpdateContactsCommand, ZuoraResponse](s"accounts/${record.Id.get}", UpdateContactsCommand(soldTo = soldTo, billTo = billTo)),
      )
    } yield updateResponse).run
  }

  def updateAccountIdentityId(accountId: AccountId, identityId: String)(implicit ex: ExecutionContext): M[\/[String, ZuoraResponse]] = {
    val command = UpdateAccountIdentityIdCommand(identityId)
    val futureMonad = implicitly[Monad[M]]
    (for {
      response <- EitherT(simpleRest.put[UpdateAccountIdentityIdCommand, ZuoraResponse](s"accounts/${accountId.get}", command))
    } yield {
      response
    }).run
  }

  private def splitContactsIfNecessary(record: AccountsByCrmIdResponseRecord, newSoldToData: Option[ContactData]): M[\/[String, Unit]] = {
    val futureMonad = implicitly[Monad[M]]

    if (newSoldToData.isDefined && record.BillToId == record.SoldToId && record.BillToId.isDefined) {
      SafeLogger.info(s"account ${record.Id.get} has the same billTo and soldTo contact, cloning BillTo into a new SoldTo contact")
      (for {
        newContactId <- EitherT(cloneContact(record.BillToId.get))
        updateResponse <- EitherT(updateSoldToId(record.Id.get, newContactId))
      } yield updateResponse).run
    } else {

      futureMonad.point(\/-(()))
    }
  }

  private def updateSoldToId(accountId: String, soldToId: String): M[\/[String, Unit]] = {
    val futureMonad = implicitly[Monad[M]]

    val body = Json.obj(
      "SoldToId" -> soldToId,
    )

    futureMonad.map(simpleRest.putJson[ZuoraCrudResponse](s"object/account/$accountId", body)) {
      case \/-(ZuoraCrudResponse(false, errors, _)) => -\/(errors.mkString("; "))
      case \/-(ZuoraCrudResponse(true, _, _)) => \/-(())
      case -\/(error) => -\/(error)
    }
  }

  private def createContact(contactData: JsValue): M[\/[String, String]] = {
    val futureMonad = implicitly[Monad[M]]

    val futureResponse = simpleRest.postJson[ZuoraCrudResponse](s"/object/contact", contactData)
    futureMonad.map(futureResponse) {
      case (-\/(error)) => \/.l[String](error)
      case (\/-(ZuoraCrudResponse(true, _, Some(createdId)))) => \/.r[String](createdId)
      case (\/-(ZuoraCrudResponse(true, _, None))) => \/.l[String]("zuora returned with success=true but no id for the newly created object")
      case (\/-(ZuoraCrudResponse(false, errors, _))) => \/.l[String](errors.mkString("; "))
    }
  }

  def cloneContact(id: String): M[\/[String, String]] = {
    def removeId(contact: JsValue) = contact match {
      case JsObject(fields) => JsObject(fields.view.filterKeys(_ != "Id").toMap)
      case x => x
    }

    val response = for {
      existingContactData <- EitherT(simpleRest.getJson(s"/object/contact/$id"))
      clonedContactData = removeId(existingContactData)
      createResponse <- EitherT(createContact(clonedContactData))
    } yield createResponse

    response.run
  }

  private def updateAllAccountContacts(
      sfContactId: String,
      records: List[AccountsByCrmIdResponseRecord],
      soldTo: Option[ContactData],
      billTo: Option[ContactData],
  )(implicit ex: ExecutionContext): M[\/[String, Unit]] = {
    val futureMonad = implicitly[Monad[M]]

    if (records.isEmpty) {
      SafeLogger.warn(s"no Zuora accounts with matching crmId for sf contact $sfContactId")
      futureMonad.point(\/-())
    } else {
      SafeLogger.info(s"updating ${records.size} accounts : [${records.map(_.Id.get).mkString(", ")}]")
      val responses = records.map { record =>
        val updateSoldTo =
          if (record.sfContactId__c.contains(sfContactId)) soldTo
          else {
            SafeLogger.info(
              s"not updating sold to in zuora account ${record.Id.get} because sfContactId ($sfContactId) doesn't match for zuora contact ${record.sfContactId__c}",
            )
            None
          }
        if (updateSoldTo.isEmpty && billTo.isEmpty) {
          SafeLogger.info(s"skipping account ${record.Id.get} since soldto and billto do not need to be updated")
          futureMonad.point(\/-(()): \/[String, Unit])
        } else {
          val restResponse = updateAccountContacts(record, updateSoldTo, billTo)
          futureMonad.map(restResponse) {
            case (\/-(ZuoraResponse(true, _))) => \/.r[String](())
            case (\/-(ZuoraResponse(false, error))) =>
              \/.l[Unit](s"account id: ${record.Id.get} ${error.getOrElse("zuora responded with success = false")}")
            case (-\/(error)) => \/.l[Unit](s"account id: ${record.Id.get} $error")
          }
        }
      }

      val futureResponses = futureMonad.sequence(responses)

      futureMonad.map(futureResponses) { responses =>
        val errors = responses.collect { case (-\/(error)) => error }

        if (errors.isEmpty) \/-(()) else -\/(errors.mkString("; "))
      }
    }
  }

  def updateZuoraBySfContact(contactId: ContactId, soldTo: Option[ContactData], billTo: Option[ContactData])(implicit
      ex: ExecutionContext,
  ): M[String \/ Unit] = {
    val futureMonad = implicitly[Monad[M]]

    if (billTo.isEmpty && soldTo.isEmpty) {
      SafeLogger.warn(s"for sf contact ${contactId.salesforceContactId} no soldTo or billTo information provided so update will be skipped")
      futureMonad.point(\/-(()))
    } else {
      val response = for {
        accounts <- EitherT(getAccountByCrmId(contactId.salesforceAccountId))
        restResponse <- EitherT(
          updateAllAccountContacts(sfContactId = contactId.salesforceContactId, records = accounts.records, soldTo = soldTo, billTo = billTo),
        )
      } yield restResponse
      response.run
    }
  }

  def cancelSubscription(
      subscriptionName: Name,
      termEndDate: LocalDate,
      maybeChargedThroughDate: Option[
        LocalDate,
      ], // FIXME: Optionality should probably be removed and semantics changed to cancellationEffectiveDate (see comments bellow)
  )(implicit ex: ExecutionContext): M[String \/ Unit] = {

    // FIXME: Not always safe assumption. There are multiple scenarios to consider
    //   1. Free trial should be explicitly handled: val cancellationEffectiveDate = if(sub.startDate <= today && sub.acceptanceDate > today) LocalDate.now
    //   2. If outside trial, and invoiced, ChargedThroughDate should always exist: val cancellationEffectiveDate = ChargedThroughDate
    //   3. If outside trial, and invoiced, but ChargedThroughDate does not exist, then it is a likely logic error. Investigate ASAP!. Currently it happens after Contributions amount change.
    val cancellationEffectiveDate =
      maybeChargedThroughDate.getOrElse(LocalDate.now) // immediate cancellation for subs which aren't yet invoiced (e.g. during digipack trial)

    val extendTermIfNeeded = maybeChargedThroughDate
      .filter(_.isAfter(termEndDate)) // we need to extend the term if they've paid past their term end date, otherwise cancel call will fail
      .map(_ =>
        EitherT(simpleRest.put[RenewSubscriptionCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}/renew", RenewSubscriptionCommand())),
      )
      .getOrElse(EitherT.right[String, M, ZuoraResponse](ZuoraResponse(success = true)))

    val cancelCommand = CancelSubscriptionCommand(cancellationEffectiveDate)

    val restResponse = for {
      _ <- extendTermIfNeeded
      cancelResponse <- EitherT(
        simpleRest.put[CancelSubscriptionCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}/cancel", cancelCommand),
      )
    } yield cancelResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def updateCancellationReason(subscriptionName: Name, userCancellationReason: String): M[String \/ Unit] = {
    val future = implicitly[Monad[M]]
    val restResponse = for {
      restResponse <- EitherT(
        simpleRest.put[UpdateCancellationSubscriptionCommand, ZuoraResponse](
          s"subscriptions/${subscriptionName.get}",
          UpdateCancellationSubscriptionCommand(cancellationReason = "Customer", userCancellationReason = userCancellationReason),
        ),
      )
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def disableAutoPay(accountId: AccountId): M[String \/ Unit] = {
    val future = implicitly[Monad[M]]

    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[DisableAutoPayCommand, ZuoraResponse](s"accounts/${accountId.get}", DisableAutoPayCommand()))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def updateChargeAmount(
      subscriptionName: Name,
      ratePlanChargeId: SubscriptionRatePlanChargeId,
      ratePlanId: RatePlanId,
      amount: Double,
      reason: String,
      applyFromDate: LocalDate,
  )(implicit ex: ExecutionContext): M[\/[String, Unit]] = {
    val updateCommand =
      UpdateChargeCommand(price = amount, ratePlanChargeId = ratePlanChargeId, ratePlanId = ratePlanId, applyFromDate = applyFromDate, note = reason)
    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[UpdateChargeCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}", updateCommand))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def getCancellationEffectiveDate(name: Name): M[String \/ Option[String]] = {
    (for {
      amendment <- EitherT(simpleRest.get[Amendment](s"amendments/subscriptions/${name.get}"))
      cancelledSub <- EitherT(simpleRest.get[CancelledSubscription](s"subscriptions/${name.get}"))
    } yield {
      if (amendment.`type`.contains("Cancellation") && cancelledSub.status == "Cancelled")
        Some(cancelledSub.subscriptionEndDate)
      else
        None
    }).run
  }

}
