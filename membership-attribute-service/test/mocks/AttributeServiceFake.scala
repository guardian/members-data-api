package mocks

import models.Attributes
import services.AttributeService
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

class AttributeServiceFake(attributes: Seq[Attributes]) extends AttributeService {
  def get(userId: String) = Future(attributes.find(_.userId == userId))
  def set(attributes: Attributes) = Future(Unit)
  def delete(userId: String) = Future(Unit)

}
