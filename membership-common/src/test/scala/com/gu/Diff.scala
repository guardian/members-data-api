package com.gu

import com.gu.memsub.subsv2.{RatePlanChargeList, PaperCharges, RatePlanCharge}
import com.gu.memsub.{ProductRatePlanChargeProductType, BillingPeriod}
import com.softwaremill.diffx.scalatest.DiffShouldMatcher._
import com.softwaremill.diffx.{DiffContext, DiffResultValue, Diff => Diffx}
import org.scalatest.Assertion
import scalaz.{Validation, \/}

object Diff {

  def assertEquals[T: Diffx](expected: T, actual: T): Assertion =
    actual shouldMatchTo (expected)

  implicit def eitherDiff[L: Diffx, R: Diffx]: Diffx[L \/ R] = Diffx.derived[L \/ R]
  implicit def validationDiff[E: Diffx, A: Diffx]: Diffx[Validation[E, A]] = Diffx.derived[Validation[E, A]]

  implicit val benefitDiff: Diffx[ProductRatePlanChargeProductType] = Diffx.diffForString.contramap(_.id)
  implicit def paidChargeListDiff[TheBenefit <: ProductRatePlanChargeProductType, TheBillingPeriod <: BillingPeriod](implicit
      e1: Diffx[PaperCharges],
      e2: Diffx[RatePlanCharge[TheBenefit, TheBillingPeriod]],
  ): Diffx[RatePlanChargeList] = (left: RatePlanChargeList, right: RatePlanChargeList, context: DiffContext) =>
    (left, right) match {
      case (cl: PaperCharges, cr: PaperCharges) =>
        Diffx[PaperCharges].apply(cl, cr)
      case (cl: RatePlanCharge[TheBenefit, TheBillingPeriod], cr: RatePlanCharge[TheBenefit, TheBillingPeriod]) =>
        Diffx[RatePlanCharge[TheBenefit, TheBillingPeriod]].apply(cl, cr)
      case _ =>
        DiffResultValue(left, right)
    }

}
