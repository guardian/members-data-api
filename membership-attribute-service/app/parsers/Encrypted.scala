package parsers

import play.api.mvc.BodyParser

object Encrypted {
  def decryptParser(array: Array[Byte]): BodyParser[Array[Byte]] = BodyParser { req =>

    decryt()
  }
}


