package sources

import java.io.File

import models.Attributes
import org.specs2.mutable.Specification

class SalesforceCSVExportSpec extends Specification {

  "SalesforceCSVExportSpec" should {
    "membersAttributes" should {
      "create a MembersAttributes iterator" in {
        val path = getClass.getResource("/contacts.csv").getPath
        val file = new File(path)

        val attributes = SalesforceCSVExport.membersAttributes(file).toList

        attributes shouldEqual List(
          Attributes("323479263", Some("Partner"), Some("292451"), MembershipJoinDate = None),
          Attributes("323479267", Some("Patron"), Some("292454"), MembershipJoinDate = None),
          Attributes("323479268", Some("Friend"), None, MembershipJoinDate = None)
        )
      }
    }
  }
}
