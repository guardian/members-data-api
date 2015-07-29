package modules

import com.github.dwhjames.awswrap.dynamodb.AmazonDynamoDBScalaMapper
import com.google.inject.AbstractModule
import configuration.Config

class DynamoDbModule extends AbstractModule {
  def configure() = {
    bind(classOf[AmazonDynamoDBScalaMapper])
      .toInstance(Config.dynamoMapper)
  }
}
