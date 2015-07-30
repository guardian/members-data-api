package services

import javax.inject.Inject

import models.{ApiResponse, MembershipAttributes}
import repositories.MembershipAttributesRepository

class AttributeService @Inject() (membershipAttributesRepo: MembershipAttributesRepository) {

  def getAttributes(userId: String): ApiResponse[MembershipAttributes] =
    membershipAttributesRepo.getAttributes(userId)

  def setAttributes(userId: String, attributes: MembershipAttributes): ApiResponse[MembershipAttributes] =
    membershipAttributesRepo.updateAttributes(userId, attributes)

}
