package com.gu.zuora.rest

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.okhttp.RequestRunners.HttpClient
import com.gu.zuora.ZuoraRestConfig
import io.lemonlabs.uri.typesafe.dsl._
import okhttp3.{FormBody, MediaType, Protocol, Request, Response, ResponseBody}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class ZuoraAccessTokenProviderTest extends AnyFlatSpec with Matchers {

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private val config = ZuoraRestConfig(
    "CODE",
    "https://rest.apisandbox.zuora.com/v1",
    "https://rest.apisandbox.zuora.com/oauth/token",
    "client-id",
    "client-secret",
  )

  private def response(request: Request, code: Int, body: String): Response =
    new Response.Builder()
      .request(request)
      .message("test")
      .code(code)
      .body(ResponseBody.create(body, MediaType.parse("application/json")))
      .protocol(Protocol.HTTP_1_1)
      .build()

  private class RecordingClient(result: Request => Future[Response]) extends HttpClient[Future] {
    var requests: List[Request] = Nil

    override def execute(request: Request)(implicit logPrefix: LogPrefix): Future[Response] = synchronized {
      requests = requests :+ request
      result(request)
    }
  }

  "ZuoraAccessTokenProvider" should "request and cache a client credentials access token" in {
    val client = new RecordingClient(request => Future.successful(response(request, 200, """{"access_token":"access-token","expires_in":3600}""")))
    val provider = new ZuoraAccessTokenProvider(config, client)

    Await.result(provider.get(), 5.seconds) shouldEqual "access-token"
    Await.result(provider.get(), 5.seconds) shouldEqual "access-token"

    client.requests should have size 1
    val request = client.requests.head
    request.url().toString shouldEqual "https://rest.apisandbox.zuora.com/oauth/token"
    request.method() shouldEqual "POST"
    val formBody = request.body().asInstanceOf[FormBody]
    (0 until formBody.size()).map(index => formBody.name(index) -> formBody.value(index)).toMap shouldEqual Map(
      "grant_type" -> "client_credentials",
      "client_id" -> "client-id",
      "client_secret" -> "client-secret",
    )
  }

  it should "refresh the access token shortly before it expires" in {
    var currentTime = Instant.parse("2026-11-04T09:00:00Z")
    var tokenNumber = 0
    val client = new RecordingClient(request => {
      tokenNumber += 1
      Future.successful(response(request, 200, s"""{"access_token":"access-token-$tokenNumber","expires_in":60}"""))
    })
    val provider = new ZuoraAccessTokenProvider(config, client, () => currentTime)

    Await.result(provider.get(), 5.seconds) shouldEqual "access-token-1"
    currentTime = currentTime.plusSeconds(31)
    Await.result(provider.get(), 5.seconds) shouldEqual "access-token-2"

    client.requests should have size 2
  }

  it should "share an in-flight token request" in {
    val tokenResponse = Promise[Response]()
    val client = new RecordingClient(_ => tokenResponse.future)
    val provider = new ZuoraAccessTokenProvider(config, client)

    val firstToken = provider.get()
    val secondToken = provider.get()
    tokenResponse.success(response(client.requests.head, 200, """{"access_token":"access-token","expires_in":3600}"""))

    Await.result(Future.sequence(List(firstToken, secondToken)), 5.seconds) shouldEqual List("access-token", "access-token")
    client.requests should have size 1
  }

  it should "fail without exposing the response body when Zuora rejects the credentials" in {
    val client = new RecordingClient(request => Future.successful(response(request, 400, """{"error":"sensitive response"}""")))
    val provider = new ZuoraAccessTokenProvider(config, client)

    val error = intercept[ZuoraAuthenticationError](Await.result(provider.get(), 5.seconds))

    error.getMessage shouldEqual "Zuora OAuth request failed with status 400"
  }

  it should "fail clearly when Zuora returns malformed JSON" in {
    val client = new RecordingClient(request => Future.successful(response(request, 200, "not JSON")))
    val provider = new ZuoraAccessTokenProvider(config, client)

    val error = intercept[ZuoraAuthenticationError](Await.result(provider.get(), 5.seconds))

    error.getMessage shouldEqual "Zuora OAuth response was not valid JSON"
  }

  it should "fail clearly when the OAuth response has an unexpected shape" in {
    val client = new RecordingClient(request => Future.successful(response(request, 200, "{}")))
    val provider = new ZuoraAccessTokenProvider(config, client)

    val error = intercept[ZuoraAuthenticationError](Await.result(provider.get(), 5.seconds))

    error.getMessage shouldEqual "Zuora OAuth response did not match the expected format"
  }

  it should "reject an invalid access token or expiry" in {
    val client = new RecordingClient(request => Future.successful(response(request, 200, """{"access_token":"","expires_in":0}""")))
    val provider = new ZuoraAccessTokenProvider(config, client)

    val error = intercept[ZuoraAuthenticationError](Await.result(provider.get(), 5.seconds))

    error.getMessage shouldEqual "Zuora OAuth response contained an invalid access token or expiry"
  }

  it should "retry after a failed token request" in {
    var requestNumber = 0
    val client = new RecordingClient(request => {
      requestNumber += 1
      if (requestNumber == 1) Future.successful(response(request, 500, "server error"))
      else Future.successful(response(request, 200, """{"access_token":"access-token","expires_in":3600}"""))
    })
    val provider = new ZuoraAccessTokenProvider(config, client)

    intercept[ZuoraAuthenticationError](Await.result(provider.get(), 5.seconds))
    Await.result(provider.get(), 5.seconds) shouldEqual "access-token"

    client.requests should have size 2
  }
}
