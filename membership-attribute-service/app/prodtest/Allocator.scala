package prodtest

class Allocator {

  def isInTest(identityId: String, percentageInTest: Double): Boolean = {
    if(percentageInTest > 0) {
      val cleaned = identityId.replaceFirst("^0+", "").toInt
      val index = cleaned % 100

      index <= percentageInTest
    } else false
  }

}
