package models

import models.subscription.BillingSchedule

case class CreditCardAccount(
    schedule: BillingSchedule,
    creditCardLastDigits: String,
)
