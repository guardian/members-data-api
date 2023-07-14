package com.gu.salesforce

import com.gu.salesforce.ContactDeserializer._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scalaz.{-\/, EitherT, \/, \/-}
import scalaz.std.scalaFuture.futureInstance

abstract class ContactRepository(implicit ec: ExecutionContext) {

  val salesforce: Scalaforce

  case class ContactRepositoryError(s: String) extends Throwable {
    override def getMessage: String = s
  }

  def upsert(userId: Option[String], values: JsObject): Future[ContactId] = {
    for {
      result <- salesforce.Contact.upsert(userId.map(Keys.IDENTITY_ID -> _), values)
    } yield new ContactId {
      override def salesforceContactId: String = result.Id
      override def salesforceAccountId: String = result.AccountId
    }
  }

  def updateIdentityId(contact: ContactId, newIdentityId: String): Future[Throwable \/ Unit] = {
    salesforce.Contact
      .update(SFContactId(contact.salesforceContactId), Keys.IDENTITY_ID, newIdentityId)
      .map(\/.r[Throwable].apply)
      .recover { case e: Throwable => \/.l[Unit](e) }
  }

  import com.gu.memsub.subsv2.reads.Trace.{Traceable => T1}
  import com.gu.memsub.subsv2.services.Trace.Traceable

  private def toEither[A](j: JsResult[A]): String \/ A = j.fold(
    { errors =>
      \/.left[String, A](errors.toString)
    },
    \/.right,
  )

  private def get(key: String, value: String): Future[String \/ Option[Contact]] = {
    salesforce.Contact.read(key, value).map { failableJsonContact =>
      (for {
        resultOpt <- failableJsonContact
        maybeContact <- resultOpt match {
          case Some(jsValue) =>
            toEither(jsValue.validate[Contact].withTrace(s"SF001: Invalid read Contact response from Salesforce for $key $value: $jsValue"))
              .map[Option[Contact]](Some.apply)
          case None => \/.r[String](None: Option[Contact])
        }
      } yield maybeContact).withTrace(s"SF002: could not get read contact response for $key $value")
    }
  }

  def get(identityId: String): Future[String \/ Option[Contact]] = // this returns right of None if the person isn't a member
    get(Keys.IDENTITY_ID, identityId)

  def getByContactId(contactId: String): Future[\/[String, Contact]] =
    get(Keys.CONTACT_ID, contactId).map {
      case \/-(Some(theContact)) => \/-(theContact)
      case \/-(None) => -\/(s"SF004: contact $contactId not found")
      case -\/(error) => -\/(s"Error retrieving contact: $contactId. Error: $error")
    }

  def getByAccountId(accountId: String): Future[String \/ Contact] = {
    val handler = new GetByAccountIdHandler(accountId)
    (for {
      responseJson <- EitherT(salesforce.Query.execute(handler.query))
      personContactId <- EitherT(Future.successful(handler.responseParser(responseJson)))
      buyerContact <- EitherT(getByContactId(personContactId.get))
    } yield buyerContact).run
  }

  private class GetByAccountIdHandler(accountId: String) {

    case class PersonContactId(get: String)
    case class PersonContactIdResponse(records: List[PersonContactId])

    implicit val PersonContactIdFormatter = new Reads[PersonContactId] {
      override def reads(json: JsValue) = JsSuccess(
        PersonContactId(
          get = (json \ "Person_Contact__c").as[String],
        ),
      )
    }

    implicit val PersonContactIdResponseFormatter = new Reads[PersonContactIdResponse] {
      override def reads(json: JsValue) = JsSuccess(
        PersonContactIdResponse(
          records = (json \ "records").as[List[PersonContactId]],
        ),
      )
    }

    val query = s"SELECT Person_Contact__c FROM Account WHERE Account.Id = '$accountId'"

    def responseParser(responseJson: JsValue): \/[String, PersonContactId] = {
      responseJson.asOpt[PersonContactIdResponse] match {
        case Some(personContactIds) =>
          personContactIds.records.headOption.map(\/.r[String].apply).getOrElse(\/.l[PersonContactId](s"Account: $accountId has no Person Contact."))
        case None => \/.l[PersonContactId](s"Query: $query returned an invalid response")
      }
    }
  }
}
