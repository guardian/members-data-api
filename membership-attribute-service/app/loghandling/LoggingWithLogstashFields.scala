package loghandling

import loghandling.LoggingField._
import play.api.Logger
import net.logstash.logback.marker.LogstashMarker
import net.logstash.logback.marker.Markers._
import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

trait LoggingWithLogstashFields {

  lazy implicit val log: Logger = Logger(getClass)

  def logInfoWithCustomFields(message: String, customFields: List[LogField]): Unit = {
    log.logger.info(customFieldMarkers(customFields), message)
  }

  def logWarnWithCustomFields(message: String, customFields: List[LogField]): Unit = {
    log.logger.warn(customFieldMarkers(customFields), message)
  }

}

object LoggingField {
  /*
   * Passing custom fields into the logs
   * Fields are passed as a map (fieldName -> fieldValue)
   * Supported field value types: Int, String
   */

  sealed trait LogField {
    def name: String
  }
  case class LogFieldInt(name: String, value: Int) extends LogField
  case class LogFieldString(name: String, value: String) extends LogField

  implicit def tupleToLogFieldInt(t: (String, Int)): LogFieldInt = LogFieldInt(t._1, t._2)
  implicit def tupleToLogFieldString(t: (String, String)): LogFieldString = LogFieldString(t._1, t._2)

  def customFieldMarkers(fields: List[LogField]) : LogstashMarker = {
    val fieldsMap = fields.map {
      case LogFieldInt(n, v) => (n, v)
      case LogFieldString(n, v) => (n, v)
    }
      .toMap
      .asJava
    appendEntries(fieldsMap)
  }
}
