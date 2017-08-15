package loghandling

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.LazyLogging
import okhttp3.{Request, Response}

import scala.concurrent.{ExecutionContext, Future}

object ZuoraRequestCounter extends LoggingWithLogstashFields {

  private val counter = new AtomicInteger()

  def withZuoraRequestCounter(runner: (Request) => Future[Response])(implicit ec: ExecutionContext): (Request) => Future[Response] = { request =>
    val inProgress = counter.incrementAndGet()
    logInfoWithCustomFields(s"started request to zuora, now have $inProgress in progress", List("zuora_concurrency_count" -> inProgress))
    val response = runner(request)
    response.onComplete(_ => counter.decrementAndGet())
    response
  }

  def get = counter.get()

}
