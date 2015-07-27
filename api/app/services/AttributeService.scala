package services

import models.{ApiResponse, MembershipAttributes}
import org.joda.time.LocalDate

class AttributeService {

  def getAttributes(userId: String): ApiResponse[MembershipAttributes] =
    ApiResponse.Right(MembershipAttributes(LocalDate.now, "patron", "1234"))
}
