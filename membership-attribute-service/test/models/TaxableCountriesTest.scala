package models

import com.gu.i18n.Country.{Canada, UK, US}
import org.specs2.mutable.Specification

class TaxableCountriesTest extends Specification {

  "TaxableCountries.isTaxable" should {

    "return true for taxable countries" in {
      Set(Canada).map { country =>
        TaxableCountries.isTaxable(country) shouldEqual true
      }.toList
    }

    "return false for non-taxable countries" in {
      Set(UK, US).map { country =>
        TaxableCountries.isTaxable(country) shouldEqual false
      }.toList
    }

  }
}
