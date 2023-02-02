package services.salesforce

import services.salesforce.model.{Contact, ContactId}
import monitoring.CreateMetrics
import play.api.libs.json.JsObject
import scalaz.\/

import scala.concurrent.{ExecutionContext, Future}

class ContactRepositoryWithMetrics(private val wrapped: ContactRepository, private val createMetrics: CreateMetrics)(implicit
    val executionContext: ExecutionContext,
) extends ContactRepository {
  lazy val metrics = createMetrics.forService(wrapped.getClass)

  override def upsert(userId: Option[String], values: JsObject): Future[ContactId] =
    metrics.measureDuration("upsert")(wrapped.upsert(userId, values))

  override def updateIdentityId(contact: ContactId, newIdentityId: String): Future[Throwable \/ Unit] =
    metrics.measureDuration("updateIdentityId")(wrapped.updateIdentityId(contact, newIdentityId))

  override def get(identityId: String): Future[String \/ Option[Contact]] =
    metrics.measureDuration("get")(wrapped.get(identityId))

  override def getByContactId(contactId: String): Future[String \/ Contact] =
    metrics.measureDuration("getByContactId")(wrapped.getByContactId(contactId))

  override def getByAccountId(accountId: String): Future[String \/ Contact] =
    metrics.measureDuration("getByAccountId")(wrapped.getByAccountId(accountId))

  override def update(contactId: String, contactFields: Map[String, String]): Future[Unit] =
    metrics.measureDuration("update")(wrapped.update(contactId, contactFields))
}
