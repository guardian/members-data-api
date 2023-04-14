package com.gu.memsub

sealed trait PaymentCardUpdateResult
case class CardUpdateSuccess(newPaymentCard: PaymentCard) extends PaymentCardUpdateResult
case class CardUpdateFailure(`type`: String, message: String, code: String) extends PaymentCardUpdateResult
