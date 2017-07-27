package prodtest

object Allocator {

  def isInTest(identityId: String, percentageInTest: Double): Boolean = {
    val cleaned = identityId.replaceFirst("^0+", "").toInt
    val index = cleaned % 100

    index < percentageInTest
  }

}
