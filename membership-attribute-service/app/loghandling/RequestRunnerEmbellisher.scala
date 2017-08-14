package loghandling

import okhttp3.{Request, Response}

import scala.concurrent.{ExecutionContext, Future}

object RequestRunnerEmbellisher {

  def withZuoraRequestCounter(runner: (Request) => Future[Response])(implicit ec: ExecutionContext): (Request) => Future[Response] = request => {
    ZuoraRequestCounter.increment
    val response = runner(request)
    response.onComplete(_ => ZuoraRequestCounter.decrement)
    response
  }
}
