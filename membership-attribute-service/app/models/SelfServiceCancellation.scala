package models

import com.gu.i18n.Country
import com.gu.memsub.Product

/*
 * this file aims to model https://docs.google.com/spreadsheets/d/1GydjiURBMRk8S_xD4iwbbIBpuXXYI5h3_M87DtRDV8I
 * */

case class SelfServiceCancellation(
    isAllowed: Boolean,
    shouldDisplayEmail: Boolean,
    phoneRegionsToDisplay: List[String],
)

object SelfServiceCancellation {

  private val ukRowPhone = "UK & ROW"
  private val usaPhone = "US"
  private val ausPhone = "AUS"
  private val allPhones = List(ukRowPhone, usaPhone, ausPhone)

  def apply(product: Product, billingCountry: Option[Country]): SelfServiceCancellation = (product, billingCountry) match {

    case (Product.Membership | Product.Contribution, _) =>
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = allPhones,
      )

    case (_, Some(Country.UK)) =>
      SelfServiceCancellation(
        isAllowed = false,
        shouldDisplayEmail = false,
        phoneRegionsToDisplay = List(ukRowPhone),
      )

    case (_, Some(Country.US) | Some(Country.Canada)) =>
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = List(usaPhone),
      )

    case (_, Some(Country.Australia) | Some(Country.NewZealand)) =>
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = allPhones,
      )

    case _ => // ROW
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = allPhones,
      )

  }
}
