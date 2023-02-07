package models.subscription.promo

import akka.actor.ActorSystem
import models.subscription.promo.Formatters.PromotionFormatters._
import models.subscription.promo.Promotion.AnyPromotion
import models.subscription.services.JsonDynamoService
import models.subscription.util.ScheduledTask
import com.typesafe.config.Config
import sun.awt.AWTAutoShutdown

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait PromotionCollection {
  def all: Seq[AnyPromotion]

  def futureAll: Future[Seq[AnyPromotion]]
}

// a basic list backed collection of promotions
case class StaticPromotionCollection(all: AnyPromotion*) extends PromotionCollection {
  val futureAll = Future.successful(all)
}

// a collection of promotions backed by DynamoDB
class DynamoPromoCollection(promoStorage: JsonDynamoService[AnyPromotion, Future], intervalPeriod: FiniteDuration = 5.minutes)(implicit
    e: ExecutionContext,
    a: ActorSystem,
) extends PromotionCollection {
  val initialFuturePromotions = promoStorage.all
  val futureTask = initialFuturePromotions.map { initialPromos =>
    val task = ScheduledTask[Seq[AnyPromotion]](
      taskName = "Promotions",
      initValue = initialPromos,
      initDelay = intervalPeriod,
      intervalPeriod = intervalPeriod,
    )(promoStorage.all)
    task.start()
    task
  }

  override def all: Seq[AnyPromotion] =
    if (futureTask.isCompleted)
      Await.result(futureTask, 0.second).get
    else Seq.empty

  override def futureAll = futureTask.map(_.get)
}

object DynamoPromoCollection {
  def forStage(config: Config, stage: String)(implicit e: ExecutionContext, a: ActorSystem): DynamoPromoCollection = {
    new DynamoPromoCollection(JsonDynamoService.forTable[AnyPromotion](DynamoTables.promotions(config, stage)))
  }
}
