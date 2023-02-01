package services.zuora.soap.actions

import services.zuora.soap.models.Result
import services.zuora.soap.writers.XmlWriter

import scala.xml.Elem

/** This bridges the more comprehensive "Action" abstraction and the newer, simpler "XmlWriter" abstraction. "Actions" also handle authentication and
  * the result type of the action, "XmlWriters" only handle going from an object to XML This allows XMLWriters to write very simple struct like
  * objects
  */
class XmlWriterAction[I, T <: Result](i: I)(implicit xmlWriter: XmlWriter[I]) extends Action[T] {
  lazy val result = XmlWriter.write(i)
  override protected val body: Elem = result.value
  override def additionalLogInfo = result.written
}
