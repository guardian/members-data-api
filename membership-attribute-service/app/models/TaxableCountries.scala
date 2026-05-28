package models

import com.gu.i18n.Country

object TaxableCountries {

  private val countries: Set[Country] = Set(Country.Canada)

  def isTaxable(bc: Country): Boolean =
    countries.contains(bc)

}
