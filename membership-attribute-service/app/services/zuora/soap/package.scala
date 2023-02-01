package services.zuora

package object soap {

  trait ZuoraException extends Throwable

  case class ZuoraServiceError(s: String) extends ZuoraException {
    override def getMessage: String = s
  }

  case class ZuoraQueryException(s: String) extends ZuoraException {
    override def getMessage: String = s
  }
}
