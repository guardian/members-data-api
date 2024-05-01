package services.salesforce

import com.gu.salesforce.{Contact, ContactId}
import play.api.libs.json._
import scalaz.\/

import scala.concurrent.Future

trait ContactRepository {

  def get(identityId: String): Future[String \/ Option[Contact]]

  def update(contactId: String, contactFields: Map[String, String]): Future[Unit]
}
