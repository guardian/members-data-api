package com.gu.services.model
import com.gu.memsub.BillingSchedule

case class CreditCardAccount(
    schedule: BillingSchedule,
    creditCardLastDigits: String,
)
