package com.gu.salesforce

import org.apache.pekko.actor.ActorSystem
import com.gu.salesforce.job.Implicits._
import com.gu.salesforce.job._
import com.gu.memsub.util.Timing
import com.gu.monitoring.SafeLogger
import okhttp3.{MediaType, Request, RequestBody}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

// This is an implementation of the Salesforce Bulk API, its documentation can be found
// here: https://www.salesforce.com/us/developer/docs/api_asynch/
abstract class ScalaforceJob(implicit ec: ExecutionContext) extends Scalaforce {

  val system: ActorSystem

  private def request[T <: Result](action: Action[T])(implicit reader: Reader[T]): Future[T] = {
    val maybeAuth = periodicAuth.get
    val futureAuth = maybeAuth.map(Future.successful).getOrElse(authorize)
    futureAuth.flatMap { auth =>
      val mediaType = MediaType.parse("application/xml; charset=UTF-8")
      val req = new Request.Builder()
        .url(s"${auth.instance_url}/services/async/31.0/${action.url}")
        .addHeader("X-SFDC-Session", auth.access_token)

      val request = action match {
        case read: ReadAction[_] => req.get().build()
        case create: WriteAction[_] => req.post(RequestBody.create(mediaType, create.body)).build()
      }

      Timing.record(metrics, action.name) {
        issueRequest(request).map { response =>
          reader.read(response) match {
            case Left(error) =>
              SafeLogger.warn(s"Salesforce action ${action.name} failed with response code ${response.code()}")
              SafeLogger.debug(response.body().string())
              throw error

            case Right(obj) => obj
          }
        }
      }
    }
  }

  private def getQueryResults(queries: Seq[BatchInfo]): Future[Seq[Map[String, String]]] = {
    val futureRows = queries.map { query =>
      for {
        result <- request(QueryGetResult(query))
        rows <- request(QueryGetRows(query, result))
      } yield rows.records
    }
    Future.reduce(futureRows)(_ ++ _)
  }

  private def completeJob(job: JobInfo): Future[Seq[BatchInfo]] = {
    val promise = Promise[Seq[BatchInfo]]()

    system.scheduler.scheduleOnce(10.seconds) {
      request(JobGetBatchList(job)).map {
        case FailedBatchList(batch) =>
          promise.failure(ScalaforceError(s"Batch ${batch.id} failed in job ${batch.jobId}, ${batch.stateMessage}"))

        case CompletedBatchList(batches) =>
          request(JobClose(job))

          promise.success(batches)

        case InProcessBatchList() => promise.completeWith(completeJob(job))
      }
    }

    promise.future
  }

  object Job {
    def query(objType: String, queries: Seq[String]): Future[Seq[Map[String, String]]] = {
      if (queries.isEmpty) {
        Future.successful(Nil)
      } else {
        Timing.record(metrics, "Query Job") {
          for {
            job <- request(JobCreate("query", objType))
            queries <- Future.sequence(queries.map { q => request(QueryCreate(job, q)) })
            _ <- completeJob(job)

            results <- getQueryResults(queries)
          } yield results
        }
      }
    }

  }
}
