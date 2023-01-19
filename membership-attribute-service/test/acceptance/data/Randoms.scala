package acceptance.data

import java.util.UUID.randomUUID
import scala.util.Random

trait Randoms {
  def randomId(prefix: String): String = prefix + "_" + randomId()

  def randomId(): String = randomUUID.toString

  def randomInt(): Int = Random.nextInt()

  def randomLong(): Long = Random.nextLong()
}

object Randoms extends Randoms
