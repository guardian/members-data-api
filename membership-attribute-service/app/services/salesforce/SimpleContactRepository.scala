package services.salesforce

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.okhttp.RequestRunners
import com.gu.okhttp.RequestRunners.FutureHttpClient
import com.gu.salesforce.ContactDeserializer._
import com.gu.salesforce.{Contact, SFContactId, SalesforceConfig, Scalaforce}
import org.apache.pekko.actor.Scheduler
import play.api.libs.json._
import scalaz.\/

import scala.concurrent.{ExecutionContext, Future}

class SimpleContactRepository(private val salesforce: Scalaforce)(implicit executionContext: ExecutionContext) extends ContactRepository {

  import com.gu.memsub.subsv2.reads.Trace.{Traceable => T1}
  import com.gu.memsub.subsv2.services.Trace.Traceable

  private def toEither[A](j: JsResult[A]): String \/ A = j.fold(
    { errors =>
      \/.left[String, A](errors.toString)
    },
    \/.right,
  )

  private def get(key: String, value: String)(implicit logPrefix: LogPrefix): Future[String \/ Option[Contact]] = {
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

  def get(
      identityId: String,
  )(implicit logPrefix: LogPrefix): Future[String \/ Option[Contact]] = // this returns right of None if the person isn't a member
    get(Keys.IDENTITY_ID, identityId)

  override def update(contactId: String, contactFields: Map[String, String])(implicit logPrefix: LogPrefix): Future[Unit] =
    salesforce.Contact.update(SFContactId(contactId), contactFields)
}

object CreateScalaforce {
  def apply(salesforceConfig: SalesforceConfig, scheduler: Scheduler, appName: String)(implicit executionContext: ExecutionContext): Scalaforce = {
    val salesforce: Scalaforce = new Scalaforce {
      val application: String = appName
      val stage: String = salesforceConfig.envName
      val sfConfig: SalesforceConfig = salesforceConfig
      val httpClient: FutureHttpClient = RequestRunners.futureRunner
      val sfScheduler = scheduler
    }
    salesforce.startAuth()
    salesforce
  }
}
