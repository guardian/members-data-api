package com.gu.memsub

object Subscription {
  case class SubscriptionNumber(getNumber: String) extends AnyVal
  case class Id(get: String) extends AnyVal
  case class AccountId(get: String) extends AnyVal
  case class AccountNumber(get: String) extends AnyVal
  case class ProductRatePlanId(get: String) extends AnyVal
  case class RatePlanId(get: String) extends AnyVal
  case class ProductId(get: String) extends AnyVal
  case class ProductRatePlanChargeId(get: String) extends AnyVal
  case class SubscriptionRatePlanChargeId(get: String) extends AnyVal

  object Feature {
    case class Id(get: String) extends AnyVal
    case class Code(get: String) extends AnyVal
  }
}
