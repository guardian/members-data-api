package com.gu.memsub

import com.gu.i18n.Currency

import scala.util.Try

object PriceParser {
  def parse(s: String): Option[Price] =
    s.replace("/Each", "").splitAt(3) match { case (code, p) =>
      for {
        currency <- Currency.fromString(code)
        price <- Try { p.toFloat }.toOption
      } yield Price(price, currency)
    }

  def unsafeParse(s: String): Price =
    parse(s).getOrElse(throw new IllegalArgumentException(s"Failed while parsing the price: $s"))
}
