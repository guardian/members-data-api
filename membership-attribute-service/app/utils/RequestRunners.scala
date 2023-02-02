package utils

import utils.okhttpscala._
import okhttp3.{OkHttpClient, Request, Response => OkResponse}
import scalaz.ReaderT

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

/** These are functions from an OkHttpRequest to an M[Response] which are passed into Clients (such as SimpleClient), to determine how they process
  * HTTP requests
  */
object RequestRunners {
  lazy val client = new OkHttpClient()
  type FutureHttpClient = Request => Future[OkResponse]
  type LoggingHttpClient[A[_]] = Request => ReaderT[String, A, OkResponse]

  /** Standard no frills run this request and return a response asynchronously A solid choice for the beginner SimpleClient user
    */
  def futureRunner(implicit ec: ExecutionContext): Request => Future[OkResponse] =
    client.execute

  /** Adjusts the standard client used in futureRunner to use a configurable read timeout setting, see:
    * https://github.com/square/okhttp/wiki/Recipes#timeouts
    */
  def configurableFutureRunner(timeout: Duration)(implicit ec: ExecutionContext): Request => Future[OkResponse] = {
    val seconds: Int = timeout.toSeconds.toInt
    client.newBuilder().readTimeout(seconds, TimeUnit.SECONDS).build().execute
  }
}
