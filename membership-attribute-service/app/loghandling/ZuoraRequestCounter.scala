package loghandling

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.LazyLogging

object ZuoraRequestCounter extends LazyLogging{
  private val counter = new AtomicInteger()

  def increment: Int = counter.incrementAndGet()

  def decrement: Int = counter.decrementAndGet()

  def get = counter.get()
}
