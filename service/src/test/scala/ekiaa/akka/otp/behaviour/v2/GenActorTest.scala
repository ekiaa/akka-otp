package ekiaa.akka.otp.behaviour.v2

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration

case object GetCounter extends Request

case object IncrementCounter extends Request

case class CounterValue(value: Int) extends Response

case class Counter(value: Int)

class TestClientModule(server: ActorRef) extends Module[Counter] {

  override def init: Counter = {
    Counter(0)
  }

  override def handleRequest(request: Request, counter: Counter): Result = {
    request match {
      case GetCounter =>
        Reply(CounterValue(counter.value))
      case IncrementCounter =>
        Postpone(
          RequestPrepack(IncrementCounter, server),
          { (response, _: Counter) =>
            response match {
              case response@CounterValue(newValue) =>
                Reply(response, Counter(newValue))
            }
          }
        )
    }
  }
}

class TestServerModule extends Module[Counter] {

  override def init: Counter = {
    Counter(0)
  }

  override def handleRequest(request: Request, counter: Counter): Result = {
    request match {
      case GetCounter =>
        Reply(CounterValue(counter.value))
      case IncrementCounter =>
        val newCounter = counter.copy(value = counter.value + 1)
        Reply(CounterValue(newCounter.value), newCounter)
    }
  }
}

class GenActorTest
  extends TestKit(ActorSystem("TestActorSystem"))
    with WordSpecLike with Matchers with MockFactory {

  private val testServerActor = system.actorOf(Props(classOf[GenActor[Counter]], new TestServerModule))
  private val testClientActor = system.actorOf(Props(classOf[GenActor[Counter]], new TestClientModule(testServerActor)))

  "A Client" when {

    "receive IncrementCounter request" should {

      "return Server counter new value" in {

        val request1 = RequestMessage(GetCounter, testClientActor, testActor)
        testClientActor ! request1
        receiveOne(FiniteDuration(5, TimeUnit.SECONDS)) match {
          case ResponseMessage(CounterValue(counterValue), id) =>
            counterValue should ===(0)
            id should ===(request1.id)
        }

        val request2 = RequestMessage(GetCounter, testServerActor, testActor)
        testServerActor ! request2
        receiveOne(FiniteDuration(5, TimeUnit.SECONDS)) match {
          case ResponseMessage(CounterValue(counterValue), id) =>
            counterValue should ===(0)
            id should ===(request2.id)
        }

        val request3 = RequestMessage(IncrementCounter, testClientActor, testActor)
        testClientActor ! request3
        receiveOne(FiniteDuration(5, TimeUnit.SECONDS)) match {
          case ResponseMessage(CounterValue(counterValue), id) =>
            counterValue should ===(1)
            id should ===(request3.id)
        }

        val request4 = RequestMessage(GetCounter, testServerActor, testActor)
        testServerActor ! request4
        receiveOne(FiniteDuration(5, TimeUnit.SECONDS)) match {
          case ResponseMessage(CounterValue(counterValue), id) =>
            counterValue should ===(1)
            id should ===(request4.id)
        }

      }

    }

  }

}
