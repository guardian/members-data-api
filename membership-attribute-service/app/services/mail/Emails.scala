package services.mail

import com.gu.i18n.Currency
import com.gu.salesforce.Contact

object Emails {
  def paymentMethodChangedEmail(emailAddress: String, contact: Contact, paymentMethod: PaymentType): EmailData = {
    EmailData(
      emailAddress = emailAddress,
      salesforceContactId = contact.salesforceContactId,
      campaignName = "payment-method-changed-email",
      dataPoints = Map(
        "first_name" -> contact.firstName.getOrElse(""),
        "last_name" -> contact.lastName,
        "payment_method" -> paymentMethod.valueForEmail,
      ),
    )
  }

  def updateAmountEmail(email: String, contact: Contact, newPrice: BigDecimal, currency: Currency) = {
    EmailData(
      email,
      contact.salesforceContactId,
      "payment-amount-change-email",
      Map(
        "first_name" -> contact.firstName.getOrElse(""),
        "last_name" -> contact.lastName,
        "new_amount" -> newPrice.toString(),
        "currency" -> currency.iso,
      ),
    )
  }
}

class PaymentType(val valueForEmail: String)
case object Card extends PaymentType("card")
case object DirectDebit extends PaymentType("direct_debit")
