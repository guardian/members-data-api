package models

import com.gu.i18n.Country.{Australia, Canada, Ireland, NewZealand, UK, US}
import com.gu.memsub.Product._
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

class SelfServiceCancellationTest extends Specification {
  val allCountries = Set(UK, Australia, US, Canada, NewZealand, Ireland)
  private val weeklies = Set(WeeklyDomestic, WeeklyRestOfWorld, WeeklyZoneA, WeeklyZoneB, WeeklyZoneC)
  val allProducts = Set(
    Membership,
    Contribution,
    SupporterPlus,
    TierThree,
    AdLite,
    Voucher,
    Delivery,
    DigitalVoucher,
    Digipack,
    GuardianPatron,
  ) ++ weeklies

  "SelfServiceCancellation.apply" should {

    "allow cancellation for Membership in all countries" in {
      allCountries.toList.map { country =>
        SelfServiceCancellation(Membership, Some(country)).isAllowed shouldEqual true
      }
    }

    "allow cancellation for Contribution in all countries" in {
      allCountries.map { country =>
        SelfServiceCancellation(Contribution, Some(country)).isAllowed shouldEqual true
      }.toList
    }

    "allow cancellation for Tier Three in all countries" in {
      allCountries.map { country =>
        SelfServiceCancellation(TierThree, Some(country)).isAllowed shouldEqual true
      }.toList
    }

    "allow cancellation for Guardian Ad-Lite in all countries" in {
      allCountries.map { country =>
        SelfServiceCancellation(AdLite, Some(country)).isAllowed shouldEqual true
      }.toList
    }

    "allow cancellation for Guardian Weekly in the UK" in {
      weeklies.map { product =>
        SelfServiceCancellation(product, Some(UK)).isAllowed shouldEqual true
      }.toList
    }

    "disallow cancellation for all products except Membership, Contribution, Digipack, Tier Three, Guardian Ad-Lite and Supporter Plus in the UK" in {
      val productsToTest = allProducts
        .diff(Set(Membership, Contribution, SupporterPlus, Digipack, TierThree, AdLite) ++ weeklies)
        .toList
      Fragment.foreach(productsToTest) { product =>
        s"$product" in {
          SelfServiceCancellation(product, Some(UK)).isAllowed shouldEqual false
        }
      }
    }

    "allow cancellation for all products in countries other than the UK" in {
      (for {
        product <- allProducts
        country <- allCountries.diff(Set(UK))
      } yield SelfServiceCancellation(product, Some(country)).isAllowed shouldEqual true).toList
    }

    "allow cancellation for all products if country is undefined" in {
      allProducts.toList
        .map { product =>
          SelfServiceCancellation(product, None).isAllowed shouldEqual true
        }
    }
  }
}
