package services.salesforce

import services.salesforce.model.{Contact, ContactId}
import play.api.libs.json._
import scalaz.\/

import scala.concurrent.Future

trait ContactRepository {
  def upsert(userId: Option[String], values: JsObject): Future[ContactId]

  def updateIdentityId(contact: ContactId, newIdentityId: String): Future[Throwable \/ Unit]

  def get(identityId: String): Future[String \/ Option[Contact]]

  def getByContactId(contactId: String): Future[\/[String, Contact]]

  def getByAccountId(accountId: String): Future[String \/ Contact]

  def update(contactId: String, contactFields: Map[String, String]): Future[Unit]
}
