package models

import com.gu.i18n.Country
import com.gu.memsub.Product

/*
* this file aims to model https://docs.google.com/spreadsheets/d/1GydjiURBMRk8S_xD4iwbbIBpuXXYI5h3_M87DtRDV8I
* */

case class SelfServiceCancellation(
  isAllowed: Boolean,
  shouldDisplayEmail: Boolean,
  phoneRegionsToDisplay: List[String]
)

object SelfServiceCancellation {

  private val ukRowPhone = "UK & ROW"
  private val usaPhone = "US"
  private val ausPhone = "AUS"
  private val allPhones = List(ukRowPhone, usaPhone, ausPhone)

  def apply(product: Product, billingCountry: Option[Country]): SelfServiceCancellation = {

    if(product.isInstanceOf[Product.Membership] || product.isInstanceOf[Product.Contribution]){
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = allPhones
      )
    }
    else if (billingCountry.contains(Country.UK)) {
      SelfServiceCancellation(
        isAllowed = false,
        shouldDisplayEmail = false,
        phoneRegionsToDisplay = List(ukRowPhone)
      )
    }
    else if (billingCountry.contains(Country.US) || billingCountry.contains(Country.Canada)) {
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = List(usaPhone)
      )
    }
    else if (billingCountry.contains(Country.Australia) || billingCountry.contains(Country.NewZealand)) {
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = allPhones
      )
    }
    else { // ROW
      SelfServiceCancellation(
        isAllowed = true,
        shouldDisplayEmail = true,
        phoneRegionsToDisplay = allPhones
      )
    }

  }
}
