package models
import com.gu.salesforce._
import com.gu.services.model._
import play.api.libs.json._
import play.api.mvc.Results.Ok

object AccountDetails {
  implicit class ResultLike(details: (Contact, PaymentDetails)) {

    def toResult = {
      val contact = details._1
      val paymentDetails = details._2
      Ok(memberDetails(contact, paymentDetails) ++ toJson(paymentDetails))
    }
    private def memberDetails(contact: Contact, paymentDetails: PaymentDetails) =
      Json.obj("tier" -> paymentDetails.plan.name, "isPaidTier" -> (paymentDetails.plan.price.amount > 0f)) ++
        contact.regNumber.fold(Json.obj())({m => Json.obj("regNumber" -> m)})

    private def toJson(paymentDetails: PaymentDetails): JsObject = {

      val endDate = paymentDetails.chargedThroughDate
        .getOrElse(paymentDetails.termEndDate)

      val payPalEmail = paymentDetails.payPalEmail.fold(Json.obj())(e => Json.obj("payPalEmail" -> e))

      val card = paymentDetails.card.fold(Json.obj())(card => Json.obj(
        "card" -> Json.obj(
          "last4" -> card.last4,
          "type" -> card.`type`
        )
      ))

      Json.obj(
        "joinDate" -> paymentDetails.startDate,
        "optIn" -> !paymentDetails.pendingCancellation,
        "subscription" -> (card ++ payPalEmail ++ Json.obj(
          "start" -> paymentDetails.lastPaymentDate,
          "end" -> endDate,
          "nextPaymentPrice" -> paymentDetails.nextPaymentPrice,
          "nextPaymentDate" -> paymentDetails.nextPaymentDate,
          "renewalDate" -> paymentDetails.termEndDate,
          "cancelledAt" -> (paymentDetails.pendingAmendment || paymentDetails.pendingCancellation),
          "subscriberId" -> paymentDetails.subscriberId,
          "trialLength" -> paymentDetails.remainingTrialLength,
          "plan" -> Json.obj(
            "name" -> paymentDetails.plan.name,
            "amount" -> paymentDetails.plan.price.amount * 100,
            "currency" -> paymentDetails.plan.price.currency.glyph,
            "interval" -> paymentDetails.plan.interval.mkString
          )))
      )
    }
  }
}
