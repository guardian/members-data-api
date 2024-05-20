package com.gu

import com.gu.memsub.subsv2.{ChargeList, PaperCharges, SingleCharge}
import com.gu.memsub.{Benefit, BillingPeriod}
import com.softwaremill.diffx.scalatest.DiffShouldMatcher._
import com.softwaremill.diffx.{DiffContext, DiffResultValue, Diff => Diffx}
import org.scalatest.Assertion
import scalaz.{Validation, \/}

object Diff {

  def assertEquals[T: Diffx](expected: T, actual: T): Assertion =
    actual shouldMatchTo (expected)

  implicit def eitherDiff[L: Diffx, R: Diffx]: Diffx[L \/ R] = Diffx.derived[L \/ R]
  implicit def validationDiff[E: Diffx, A: Diffx]: Diffx[Validation[E, A]] = Diffx.derived[Validation[E, A]]

  implicit val benefitDiff: Diffx[Benefit] = Diffx.diffForString.contramap(_.id)
  implicit def paidChargeListDiff[TheBenefit <: Benefit, TheBillingPeriod <: BillingPeriod](implicit
      e1: Diffx[PaperCharges],
      e2: Diffx[SingleCharge[TheBenefit, TheBillingPeriod]],
  ): Diffx[ChargeList] = (left: ChargeList, right: ChargeList, context: DiffContext) =>
    (left, right) match {
      case (cl: PaperCharges, cr: PaperCharges) =>
        Diffx[PaperCharges].apply(cl, cr)
      case (cl: SingleCharge[TheBenefit, TheBillingPeriod], cr: SingleCharge[TheBenefit, TheBillingPeriod]) =>
        Diffx[SingleCharge[TheBenefit, TheBillingPeriod]].apply(cl, cr)
      case _ =>
        DiffResultValue(left, right)
    }

}
