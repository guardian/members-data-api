package services.zuora.rest

import models.subscription.Subscription._
import monitoring.SafeLogger
import services.zuora.rest.ZuoraRestService._
import _root_.services.zuora.rest.{ZuoraCrudResponse, ZuoraResponse}
import org.joda.time.LocalDate
import play.api.libs.json.{JsObject, JsValue, Json}
import scalaz.std.list._
import scalaz.{-\/, EitherT, Monad, \/, \/-}
import services.salesforce.model.ContactId

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

class SimpleClientZuoraRestService(private val simpleRest: SimpleClient)(implicit val m: Monad[Future], ec: ExecutionContext)
    extends ZuoraRestService {

  def getAccount(accountId: AccountId): Future[String \/ AccountSummary] = {
    simpleRest.get[AccountSummary](s"accounts/${accountId.get}/summary") // TODO error handling
  }

  def getObjectAccount(accountId: AccountId): Future[String \/ ObjectAccount] = {
    simpleRest.get[ObjectAccount](s"object/account/${accountId.get}")
  }

  def getAccounts(identityId: String): Future[String \/ GetAccountsQueryResponse] = {
    val queryString =
      s"select Id, Balance, Currency, DefaultPaymentMethodId, PaymentGateway, LastInvoiceDate from account where IdentityId__c = '$identityId' and Status = 'Active'"
    simpleRest.post[RestQuery, GetAccountsQueryResponse]("action/query", RestQuery(queryString))
  }

  def getAccountByCrmId(crmId: String): Future[String \/ AccountsByCrmIdResponse] = {
    val queryString = s"select Id, BillToId, SoldToId, sfContactId__c  from Account where CrmId = '$crmId'"
    simpleRest.post[RestQuery, AccountsByCrmIdResponse]("action/query", RestQuery(queryString))
  }

  def getGiftSubscriptionRecordsFromIdentityId(identityId: String): Future[String \/ List[GiftSubscriptionsFromIdentityIdRecord]] = {
    val today = LocalDate.now().toString("yyyy-MM-dd")
    val queryString =
      s"select name, id, termEndDate from subscription where GifteeIdentityId__c = '${identityId}' and status = 'Active' and termEndDate >= '$today'"
    val response = simpleRest.post[RestQuery, GiftSubscriptionsFromIdentityIdResponse]("action/query", RestQuery(queryString))
    EitherT(response).map(_.records).run
  }

  def getPaymentMethod(paymentMethodId: String): Future[String \/ PaymentMethodResponse] =
    simpleRest.get[PaymentMethodResponse](s"object/payment-method/$paymentMethodId")

  def addEmail(accountId: AccountId, email: String): Future[String \/ Unit] = {

    val future = implicitly[Monad[Future]]

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

  private def unsuccessfulResponseToLeft(restResponse: EitherT[String, Future, ZuoraResponse]): EitherT[String, Future, ZuoraResponse] = {
    val futureMonad = implicitly[Monad[Future]]

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
  ): Future[\/[String, ZuoraResponse]] = {
    val futureMonad = implicitly[Monad[Future]]

    (for {
      updated <- EitherT(splitContactsIfNecessary(record, soldTo))
      updateResponse <- EitherT(
        simpleRest.put[UpdateContactsCommand, ZuoraResponse](s"accounts/${record.Id.get}", UpdateContactsCommand(soldTo = soldTo, billTo = billTo)),
      )
    } yield updateResponse).run
  }

  def updateAccountIdentityId(accountId: AccountId, identityId: String)(implicit ex: ExecutionContext): Future[\/[String, ZuoraResponse]] = {
    val command = UpdateAccountIdentityIdCommand(identityId)
    val futureMonad = implicitly[Monad[Future]]
    (for {
      response <- EitherT(simpleRest.put[UpdateAccountIdentityIdCommand, ZuoraResponse](s"accounts/${accountId.get}", command))
    } yield {
      response
    }).run
  }

  private def splitContactsIfNecessary(record: AccountsByCrmIdResponseRecord, newSoldToData: Option[ContactData]): Future[\/[String, Unit]] = {
    val futureMonad = implicitly[Monad[Future]]

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

  private def updateSoldToId(accountId: String, soldToId: String): Future[\/[String, Unit]] = {
    val futureMonad = implicitly[Monad[Future]]

    val body = Json.obj(
      "SoldToId" -> soldToId,
    )

    futureMonad.map(simpleRest.putJson[ZuoraCrudResponse](s"object/account/$accountId", body)) {
      case \/-(ZuoraCrudResponse(false, errors, _)) => -\/(errors.mkString("; "))
      case \/-(ZuoraCrudResponse(true, _, _)) => \/-(())
      case -\/(error) => -\/(error)
    }
  }

  private def createContact(contactData: JsValue): Future[\/[String, String]] = {
    val futureMonad = implicitly[Monad[Future]]

    val futureResponse = simpleRest.postJson[ZuoraCrudResponse](s"/object/contact", contactData)
    futureMonad.map(futureResponse) {
      case (-\/(error)) => \/.l[String](error)
      case (\/-(ZuoraCrudResponse(true, _, Some(createdId)))) => \/.r[String](createdId)
      case (\/-(ZuoraCrudResponse(true, _, None))) => \/.l[String]("zuora returned with success=true but no id for the newly created object")
      case (\/-(ZuoraCrudResponse(false, errors, _))) => \/.l[String](errors.mkString("; "))
    }
  }

  def cloneContact(id: String): Future[\/[String, String]] = {
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
  ): Future[\/[String, Unit]] = {
    val futureMonad = implicitly[Monad[Future]]

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
            case \/-(ZuoraResponse(true, _)) => \/.r[String](())
            case \/-(ZuoraResponse(false, error)) =>
              \/.l[Unit](s"account id: ${record.Id.get} ${error.getOrElse("zuora responded with success = false")}")
            case -\/(error) => \/.l[Unit](s"account id: ${record.Id.get} $error")
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

  def updateZuoraBySfContact(contactId: ContactId, soldTo: Option[ContactData], billTo: Option[ContactData]): Future[String \/ Unit] = {
    val futureMonad = implicitly[Monad[Future]]

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
  )(implicit ex: ExecutionContext): Future[String \/ Unit] = {

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
      .getOrElse(EitherT.right[String, Future, ZuoraResponse](ZuoraResponse(success = true)))

    val cancelCommand = CancelSubscriptionCommand(cancellationEffectiveDate)

    val restResponse = for {
      _ <- extendTermIfNeeded
      cancelResponse <- EitherT(
        simpleRest.put[CancelSubscriptionCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}/cancel", cancelCommand),
      )
    } yield cancelResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def updateCancellationReason(subscriptionName: Name, userCancellationReason: String): Future[String \/ Unit] = {
    val future = implicitly[Monad[Future]]
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

  def disableAutoPay(accountId: AccountId): Future[String \/ Unit] = {
    val future = implicitly[Monad[Future]]

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
  )(implicit ex: ExecutionContext): Future[\/[String, Unit]] = {
    val updateCommand =
      UpdateChargeCommand(price = amount, ratePlanChargeId = ratePlanChargeId, ratePlanId = ratePlanId, applyFromDate = applyFromDate, note = reason)
    val restResponse = for {
      restResponse <- EitherT(simpleRest.put[UpdateChargeCommand, ZuoraResponse](s"subscriptions/${subscriptionName.get}", updateCommand))
    } yield restResponse

    unsuccessfulResponseToLeft(restResponse).map(_ => ()).run
  }

  def getCancellationEffectiveDate(name: Name): Future[String \/ Option[String]] = {
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
