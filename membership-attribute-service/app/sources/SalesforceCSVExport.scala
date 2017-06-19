package sources

import java.io.File

import models.Attributes
import org.slf4j.LoggerFactory

import scala.io.Source

object SalesforceCSVExport {
  private val re = """"(\w+)","(\w*)","(\w+)","(.+)"""".r
  private val logger = LoggerFactory.getLogger(getClass)

  def membersAttributes(file: File): Iterator[Attributes] = {
    Source.fromFile(file)
      .getLines().drop(1) // The export file comes with CSV headers
      .map {
        case re(id, num, tier, date) =>
          val numOpt = if (num.isEmpty) None else Some(num)
          Some(Attributes(id, Some(tier), numOpt, MembershipJoinDate = None))
        case str =>
          logger.error(s"Couldn't parse line\n$str\nas a valid members attribute")
          None
      }.collect { case Some(attrs) => attrs }
  }
}
