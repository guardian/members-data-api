package models

import org.joda.time.LocalDate
import org.specs2.mutable.Specification

class AnniversaryDateTest extends Specification {
  "anniversaryDate" should {
    "if today is equal to subscription start, then anniversary is exactly in one year" in {
      val actual = AccountDetails.anniversary(LocalDate.parse("2019-05-01"), LocalDate.parse("2019-05-01"))
      actual should_=== LocalDate.parse("2020-05-01")
    }
    "if today is before next anniversary, then stop searching and return next anniversary date" in {
      val actual = AccountDetails.anniversary(LocalDate.parse("2019-05-01"), LocalDate.parse("2020-04-28"))
      actual should_=== LocalDate.parse("2020-05-01")
    }

    "if next anniversary is many years from the subscription start, then keep moving year by year until today is just before next anniversary date" in {
      val actual = AccountDetails.anniversary(LocalDate.parse("2019-05-01"), LocalDate.parse("2025-05-01"))
      actual should_=== LocalDate.parse("2026-05-01")
    }
  }
}
