package com.gu.okhttp

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import okhttp3.{Call, Callback, OkHttpClient, Request, Response}

import java.io.IOException
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

/** These are functions from an OkHttpRequest to an M[Response] which are passed into Clients (such as SimpleClient), to determine how they process
  * HTTP requests
  */
object RequestRunners {
  lazy val client = new OkHttpClient()

  trait HttpClient[M[_]] {
    def execute(request: Request)(implicit logPrefix: LogPrefix): M[Response]
  }

  class FutureHttpClient(client: OkHttpClient) extends HttpClient[Future] with SafeLogging {

    def execute(request: Request)(implicit logPrefix: LogPrefix): Future[Response] = {
      val p = Promise[Response]()

      client
        .newCall(request)
        .enqueue(new Callback {
          override def onFailure(call: Call, e: IOException): Unit = {
            val sanitizedUrl = s"${request.url().uri().getHost}${request.url().uri().getPath}" // don't log query string
            logger.warn(s"okhttp request failure: ${request.method()} $sanitizedUrl", e)
            p.failure(e)
          }

          override def onResponse(call: Call, response: Response): Unit = {
            p.success(response)
          }
        })

      p.future
    }

  }

  /** Standard no frills run this request and return a response asynchronously A solid choice for the beginner SimpleClient user
    */
  def futureRunner: FutureHttpClient =
    new FutureHttpClient(client)

  /** Adjusts the standard client used in futureRunner to use a configurable read timeout setting, see:
    * https://github.com/square/okhttp/wiki/Recipes#timeouts
    */
  def configurableFutureRunner(timeout: Duration): FutureHttpClient = {
    val seconds: Int = timeout.toSeconds.toInt
    new FutureHttpClient(client.newBuilder().readTimeout(seconds, TimeUnit.SECONDS).build())
  }

}
