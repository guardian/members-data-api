package models
import com.gu.memsub.{GoCardless, PayPalMethod, PaymentCard}
import com.gu.salesforce._
import com.gu.services.model._
import org.joda.time.LocalDate
import play.api.libs.json._
import play.api.mvc.Results.Ok

object AccountDetails {

  //TODO TEST THAT THE DATE IS CORRECTLY FORMATTED AND REMOVE DUPLICATION
  implicit val localDateWrites = new  Writes[LocalDate] {
    override def writes(d: LocalDate) = JsString(d.toString("yyyy-MM-dd"))
  }

  implicit class ResultLike(details: (Contact, PaymentDetails, String)) {

    def toResult = {
      val contact = details._1
      val paymentDetails = details._2
      val stripePublicKey = details._3
      Ok(memberDetails(contact, paymentDetails) ++ toJson(paymentDetails, stripePublicKey))
    }

    private def memberDetails(contact: Contact, paymentDetails: PaymentDetails) =
      Json.obj("tier" -> paymentDetails.plan.name, "isPaidTier" -> (paymentDetails.plan.price.amount > 0f)) ++
        contact.regNumber.fold(Json.obj())({m => Json.obj("regNumber" -> m)})

    private def toJson(paymentDetails: PaymentDetails, stripePublicKey: String): JsObject = {

      val endDate = paymentDetails.chargedThroughDate
        .getOrElse(paymentDetails.termEndDate)

      val paymentMethod = paymentDetails.paymentMethod.fold(Json.obj()) {
        case payPal: PayPalMethod => Json.obj(
          "paymentMethod" -> "PayPal",
          "payPalEmail" -> payPal.email
        )
        case card: PaymentCard => Json.obj(
          "paymentMethod" -> "Card",
          "card" -> {
            Json.obj(
              "last4" -> card.paymentCardDetails.map(_.lastFourDigits).getOrElse[String]("••••"),
              "type" -> card.cardType.getOrElse[String]("unknown"),
              "stripePublicKeyForUpdate" -> stripePublicKey
            )
          }
        )
        case dd: GoCardless => Json.obj(
          "paymentMethod" -> "DirectDebit",
          "account" -> Json.obj(
            "accountName" -> dd.accountName
          )
        )
      }

      Json.obj(
        "joinDate" -> paymentDetails.startDate,
        "optIn" -> !paymentDetails.pendingCancellation,
        "subscription" -> (paymentMethod ++ Json.obj(
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
