package services

import models.Attributes

import scala.concurrent.Future

trait AttributeService {
  def get(userId: String): Future[Option[Attributes]]
  def delete(userId: String): Future[Unit]
  def set(attributes: Attributes): Future[Unit]
}
