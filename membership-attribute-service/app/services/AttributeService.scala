package services

import models.MembershipAttributes

import scala.concurrent.Future

trait AttributeService {
  def get(userId: String): Future[Option[MembershipAttributes]]
  def delete(userId: String): Future[Unit]
  def set(attributes: MembershipAttributes): Future[Unit]
}
