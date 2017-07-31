package prodtest

import com.typesafe.scalalogging.LazyLogging

import scala.util.{Failure, Success, Try}

object Allocator extends LazyLogging {

  def isInTest(identityId: String, percentageInTest: Double): Boolean = {
    Try(identityId.replaceFirst("^0+", "").toInt) match {
      case Success(cleanedInt) =>
        val index = cleanedInt % 100
        index < percentageInTest
      case Failure(e) =>
        logger.warn(s"Tried check if $identityId is eligible for a test, but $identityId is not an int.")
        false
    }
  }

}
