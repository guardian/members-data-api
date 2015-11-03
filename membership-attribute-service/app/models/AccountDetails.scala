package models
import com.gu.membership.salesforce.{Member, Contact, MemberStatus, PaymentMethod}
import com.gu.services.PaymentDetails
import play.api.libs.json._
import play.api.mvc.Results.Ok

object AccountDetails {
  implicit class ResultLike(details: (Contact[MemberStatus, PaymentMethod], PaymentDetails)) {

    def toResult = {
      val contact = details._1
      val paymentDetails = details._2
      Ok(basicDetails(contact) ++ memberDetails(contact) ++ toJson(paymentDetails))
    }

    private def memberDetails(contact: Contact[MemberStatus, PaymentMethod]): JsObject = contact.memberStatus match {
      case m: Member => Json.obj("regNumber" -> m.regNumber.mkString, "tier" -> m.tier.name, "isPaidTier" -> m.tier.isPaid)
      case _ => Json.obj()
    }

    private def basicDetails(contact: Contact[MemberStatus, PaymentMethod]) =
      Json.obj("joinDate" -> contact.joinDate)

    private def toJson(paymentDetails: PaymentDetails): JsObject = {

      val card = paymentDetails.plan.card.fold(Json.obj())(card => Json.obj(
        "card" -> Json.obj(
          "last4" -> card.last4,
          "type" -> card.`type`
        )
      ))

      Json.obj(
        "optIn" -> !paymentDetails.pendingCancellation,
        "subscription" -> (card ++ Json.obj(
          "start" -> paymentDetails.startDate,
          "end" -> paymentDetails.termEndDate,
          "nextPaymentPrice" -> paymentDetails.nextPaymentPrice,
          "nextPaymentDate" -> paymentDetails.nextPaymentDate,
          "renewalDate" -> paymentDetails.termEndDate,
          "cancelledAt" -> paymentDetails.pendingAmendment,
          "plan" -> Json.obj(
            "name" -> paymentDetails.plan.name,
            "amount" -> paymentDetails.plan.amount,
            "interval" -> (if (paymentDetails.plan.interval.getOrElse("") == "Annual") "year" else "month")
          )))
      )
    }
  }
}
