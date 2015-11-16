package mocks

import com.gu.membership.salesforce.{PaymentMethod, MemberStatus, Contact}
import com.gu.services.PaymentService
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.Plan
import play.api.libs.concurrent.Execution.Implicits._

import org.joda.time.DateTime
import scala.concurrent.Future

class PaymentServiceStub extends PaymentService {

  val start = new DateTime("2015-01-01T00:00:00Z")
  val acceptance = new DateTime("2015-01-01T00:00:00Z")
  val nextPayment = new DateTime("2016-01-01T00:00:00Z")
  val termEnd = new DateTime("2015-12-31T11:59:59Z")

  val plan = Plan("plan name", 10, None, None)
  val paymentDetails = PaymentDetails(pendingCancellation = false, start, acceptance, 10, nextPayment, termEnd, pendingAmendment = false, plan)
  def paymentDetails(contact: Contact[MemberStatus, PaymentMethod], productType: String) = Future(paymentDetails)
}