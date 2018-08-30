package models

import enumeratum.{CirceEnum, Enum, EnumEntry}

import scala.collection.immutable.IndexedSeq

// Models currencies supported by the API
sealed trait PaymentStatus extends EnumEntry

object PaymentStatus extends Enum[PaymentStatus] with CirceEnum[PaymentStatus] {

  override val values: IndexedSeq[PaymentStatus] = findValues

  case object Paid extends PaymentStatus

  case object Failed extends PaymentStatus

  case object Refunded extends PaymentStatus
}
