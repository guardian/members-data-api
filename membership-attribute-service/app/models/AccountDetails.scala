package models
import com.gu.memsub.{GoCardless, PayPalMethod, PaymentCard}
import com.gu.services.model.PaymentDetails
import json.localDateWrites
import play.api.libs.json.{Json, _}

case class AccountDetails(regNumber: Option[String], email: Option[String], paymentDetails: PaymentDetails, stripePublicKey: String, membershipAlertText: Option[String])

object AccountDetails {

  implicit class ResultLike(accountDetails: AccountDetails) {

    import accountDetails._

    def toJson: JsObject = {

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
              "stripePublicKeyForUpdate" -> stripePublicKey,
              "email" -> email
            )
          }
        )
        case dd: GoCardless => Json.obj(
          "paymentMethod" -> "DirectDebit",
          "account" -> Json.obj( // DEPRECATED
            "accountName" -> dd.accountName
          ),
          "mandate" -> Json.obj(
            "accountName" -> dd.accountName,
            "accountNumber" -> dd.accountNumber,
            "sortCode" -> dd.sortCode
          )
        )
      }

      Json.obj(
        "tier" -> paymentDetails.plan.name,
        "isPaidTier" -> (paymentDetails.plan.price.amount > 0f)
      ) ++
        regNumber.fold(Json.obj())({reg => Json.obj("regNumber" -> reg)}) ++
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
            "subscriberId" -> paymentDetails.subscriberId, // TODO remove once nothing is using this key (same time as removing old deprecated endpoints
            "subscriptionId" -> paymentDetails.subscriberId,
            "trialLength" -> paymentDetails.remainingTrialLength,
            "plan" -> Json.obj(
              "name" -> paymentDetails.plan.name,
              "amount" -> paymentDetails.plan.price.amount * 100,
              "currency" -> paymentDetails.plan.price.currency.glyph,
              "currencyISO" -> paymentDetails.plan.price.currency.iso,
              "interval" -> paymentDetails.plan.interval.mkString
            )))
        ) ++ membershipAlertText.fold(Json.obj())({text => Json.obj("alertText" -> text)})

    }
  }
}
