package mocks
import com.gu.membership.salesforce.ContactRepository
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.JsObject
import scala.concurrent.Future

class ContactRepositoryDummy extends ContactRepository {
  override def upsert(userId: String, values: JsObject) = Future(throw new Exception)
  override def get(userId: String) = Future(None)
  val salesforce = null //forgive me
}