package com.gu.lib

import com.gu.monitoring.SafeLogging
import okhttp3._

import java.io.IOException
import scala.concurrent.{Future, Promise}

package object okhttpscala {

  implicit class RickOkHttpClient(client: OkHttpClient) extends SafeLogging {

    def execute(request: Request): Future[Response] = {
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
}
