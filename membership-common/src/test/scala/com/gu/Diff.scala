package com.gu

import com.gu.memsub.ProductRatePlanChargeProductType
import com.softwaremill.diffx.scalatest.DiffShouldMatcher._
import com.softwaremill.diffx.{Diff => Diffx}
import org.scalatest.Assertion
import scalaz.{Validation, \/}

object Diff {

  def assertEquals[T: Diffx](expected: T, actual: T): Assertion =
    actual shouldMatchTo (expected)

  implicit def eitherDiff[L: Diffx, R: Diffx]: Diffx[L \/ R] = Diffx.derived[L \/ R]
  implicit def validationDiff[E: Diffx, A: Diffx]: Diffx[Validation[E, A]] = Diffx.derived[Validation[E, A]]

  implicit val benefitDiff: Diffx[ProductRatePlanChargeProductType] = Diffx.diffForString.contramap(_.id)

}
