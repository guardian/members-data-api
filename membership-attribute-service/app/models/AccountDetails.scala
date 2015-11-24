package models
import com.gu.membership.salesforce._
import com.gu.services.model._
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
      case m: PaidTierMember => Json.obj("regNumber" -> m.regNumber.mkString, "tier" -> m.tier.name, "isPaidTier" -> m.tier.isPaid)
      case m: FreeTierMember => Json.obj("tier" -> m.tier.name, "isPaidTier" -> m.tier.isPaid)
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
          "subscriberId" -> paymentDetails.subscriberId,
          "trialLength" -> paymentDetails.remainingTrialLength,
          "plan" -> Json.obj(
            "name" -> paymentDetails.plan.name,
            "amount" -> paymentDetails.plan.amount,
            "interval" -> paymentDetails.plan.interval.mkString
          )))
      )
    }
  }
}
