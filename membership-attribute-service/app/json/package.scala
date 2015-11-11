import play.api.libs.functional.syntax._
import play.api.libs.json.{OWrites, Writes, _}


package object json {
  // Adapted from http://kailuowang.blogspot.co.uk/2013/11/addremove-fields-to-plays-default-case.html
  implicit class RichOWrites[A](writes: OWrites[A]) {
    def addField[T: Writes](fieldName: String, field: A => T): OWrites[A] =
      (writes ~ (__ \ fieldName).write[T])((a: A) => (a, field(a)))

    def removeField(fieldName: String): OWrites[A] = OWrites { a: A =>
      val transformer = (__ \ fieldName).json.prune
      Json.toJson(a)(writes).validate(transformer).get
    }
  }
}
