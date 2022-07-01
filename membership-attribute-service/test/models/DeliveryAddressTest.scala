package models

import org.specs2.mutable.Specification

class DeliveryAddressTest extends Specification {

  "splitAddressLines" should {
    "split address line into two at comma" in {
      DeliveryAddress.splitAddressLines(
        Some("25, Low Road"),
      ) should_=== Some(("25", "Low Road"))
    }
    "split address line into two at last comma" in {
      DeliveryAddress.splitAddressLines(
        Some("Flat 4, Floor 7, 25, Low Road, Halfmoon Street"),
      ) should_=== Some(("Flat 4, Floor 7, 25, Low Road", "Halfmoon Street"))
    }
    "leave address line alone if has no comma" in {
      DeliveryAddress.splitAddressLines(
        Some("25 Low Road"),
      ) should_=== Some(("25 Low Road", ""))
    }
    "leave address line alone if empty" in {
      DeliveryAddress.splitAddressLines(Some("")) should_=== Some(("", ""))
    }
    "leave address line alone if not defined" in {
      DeliveryAddress.splitAddressLines(None) should_=== None
    }
    "trim whitespace" in {
      DeliveryAddress.splitAddressLines(
        Some("Flat 4, Floor 7, 25, Low Road,  Halfmoon Street"),
      ) should_=== Some(("Flat 4, Floor 7, 25, Low Road", "Halfmoon Street"))
    }
  }
}
