package com.gu.zuora.soap

import com.gu.memsub.util.FutureRetry._
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.{NoOpZuoraMetrics, SafeLogging, ZuoraMetrics}
import com.gu.okhttp.RequestRunners._
import com.gu.zuora.ZuoraSoapConfig
import com.gu.zuora.soap.Readers._
import com.gu.zuora.soap.actions.{Action, Actions}
import com.gu.zuora.soap.models.Results.{Authentication, QueryResult}
import com.gu.zuora.soap.models.{Identifiable, Query, Result}
import com.gu.zuora.soap.readers.Reader
import okhttp3.Request.Builder
import okhttp3._
import org.apache.pekko.actor.ActorSystem

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class Client(
    apiConfig: ZuoraSoapConfig,
    httpClient: FutureHttpClient,
    extendedHttpClient: FutureHttpClient,
    metrics: ZuoraMetrics = NoOpZuoraMetrics,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends SafeLogging {

  import Client._

  val clientMediaType = MediaType.parse("text/plain; charset=utf-8")

  private val periodicAuth = new AtomicReference[Authentication](null)
  actorSystem.scheduler.schedule(0.seconds, 15.minutes)(authentication())

  private def authentication(): Unit =
    retry(request(Actions.Login(apiConfig), None, authenticationReader)(noLogPrefix))(ec, actorSystem.scheduler)
      .onComplete {
        case Success(auth) =>
          periodicAuth.set(auth)
          logger.info(s"Successfully authenticated Zuora SOAP client in ${apiConfig.envName}")

        case Failure(ex) =>
          logger.error(scrub"Failed Zuora SOAP client authentication in ${apiConfig.envName}", ex)
      }

  private def request[T <: models.Result](
      action: Action[T],
      authentication: Option[Authentication],
      reader: Reader[T],
      client: FutureHttpClient = httpClient,
  )(implicit logPrefix: LogPrefix): Future[T] = {
    metrics.countRequest()
    val request = new Builder()
      .url(apiConfig.url.toString())
      .post(RequestBody.create(clientMediaType, action.xml(authentication).toString()))
      .build()
    if (action.enableLogging)
      logger.info(
        s"Zuora SOAP call in environment ${apiConfig.envName}. Request info:\n${action.prettyLogInfo}. Is authentication defined: ${authentication.isDefined}",
      )
    client.execute(request)
      .map { result =>
        val responseBody = result.body().string()
        reader.read(responseBody) match {
          case Left(error) =>
            logger.error(
              scrub"Zuora action ${action.getClass.getSimpleName} resulted in error: CODE: ${result.code} RESPONSE BODY: $responseBody Is authentication defined: ${authentication.isDefined}",
            )
            throw error

          case Right(obj) => obj
        }
      }

  }

  def isReady: Boolean = Option(periodicAuth.get()).isDefined

  def authenticatedRequest[T <: Result](action: => Action[T])(implicit reader: Reader[T], logPrefix: LogPrefix): Future[T] =
    Future.unit.flatMap(_ => request(action, Option(periodicAuth.get), reader))

  def extendedAuthenticatedRequest[T <: Result](action: => Action[T])(implicit reader: Reader[T], logPrefix: LogPrefix): Future[T] =
    Future.unit.flatMap(_ => request(action, Option(periodicAuth.get), reader, extendedHttpClient))

  def query[T <: Query](where: String)(implicit reader: readers.Query[T], logPrefix: LogPrefix): Future[Seq[T]] =
    authenticatedRequest(Actions.Query(reader.format(where))).map { case QueryResult(results) => reader.read(results) }

  def query[T <: Query](where: ZuoraFilter)(implicit reader: readers.Query[T], logPrefix: LogPrefix): Future[Seq[T]] =
    query(where.toFilterString)(reader, logPrefix)

  def queryOne[T <: Query](where: String)(implicit reader: readers.Query[T], logPrefix: LogPrefix): Future[T] =
    query(where)(reader, logPrefix).map(
      _.headOption
        .getOrElse(throw new ZuoraQueryException(s"Query '${reader.getClass.getSimpleName} $where' returned 0 results, expected one")),
    )

  def queryOne[T <: Query](where: ZuoraFilter)(implicit reader: readers.Query[T], logPrefix: LogPrefix): Future[T] =
    queryOne(where.toFilterString)(reader, logPrefix)

}

object Client {

  def parentFilter[C <: Query, P <: Query](child: C, foreignKey: (C) => String): SimpleFilter =
    SimpleFilter("Id", foreignKey(child))

  def childFilter[C <: Query, P <: Query with Identifiable](parent: P): SimpleFilter =
    SimpleFilter(parent.objectName + "Id", parent.id)

  val noLogPrefix: LogPrefix = new LogPrefix {
    override def message: String = "no-id"
  }

}
