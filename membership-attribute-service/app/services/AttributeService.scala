package services

import javax.inject.Inject

import models.MembershipAttributes
import repositories.MembershipAttributesRepository

import scala.concurrent.Future

class AttributeService @Inject() (repo: MembershipAttributesRepository) {
  def getAttributes(userId: String): Future[Option[MembershipAttributes]] = repo.getAttributes(userId)

  def setAttributes(attributes: MembershipAttributes): Future[Unit] = repo.updateAttributes(attributes)
}
