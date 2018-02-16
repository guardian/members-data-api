package filters

import javax.inject.Inject

import akka.stream.Materializer
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WS, WSClient}
import play.api.mvc._

import scala.concurrent.Future

class AddEC2InstanceHeader (wSClient: WSClient)(implicit val mat: Materializer) extends Filter {

  // http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
  lazy val instanceIdF = wSClient.url("http://169.254.169.254/latest/meta-data/instance-id").get().map(_.body)

  def apply(nextFilter: RequestHeader => Future[Result])(requestHeader: RequestHeader): Future[Result] = for {
    result <- nextFilter(requestHeader)
  } yield {
    val instanceIdOpt = instanceIdF.value.flatMap(_.toOption) // We don't want to block if the value is not available
    instanceIdOpt.fold(result)(instanceId => result.withHeaders("X-EC2-instance-id" -> instanceId))
  }

}
