package com.gu.memsub

import com.gu.i18n.Currency

case class PricingSummary(underlying: Map[Currency, Price]) {
  val prices = underlying.values
  val currencies = underlying.keySet
  val isFree = prices.map(_.amount).sum == 0
}
