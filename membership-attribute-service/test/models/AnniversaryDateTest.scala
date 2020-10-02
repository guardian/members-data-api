package models

import org.joda.time.LocalDate
import org.specs2.mutable.Specification

class AnniversaryDateTest extends Specification {
  "anniversaryDate" should {
    "if today is equal left then right" in {
      val actual = AccountDetails.anniversary(LocalDate.parse("2019-05-01"), LocalDate.parse("2019-05-01"))
      actual should_=== LocalDate.parse("2020-05-01")
    }
    "if today is between left and right then right" in {
      val actual = AccountDetails.anniversary(LocalDate.parse("2019-05-01"), LocalDate.parse("2020-04-28"))
      actual should_=== LocalDate.parse("2020-05-01")
    }

    "if today is outside left and right then eventually right" in {
      val actual = AccountDetails.anniversary(LocalDate.parse("2019-05-01"), LocalDate.parse("2025-05-01"))
      actual should_=== LocalDate.parse("2026-05-01")
    }
  }
}
