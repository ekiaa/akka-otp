package ekiaa.akka.otp.behaviour

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration

case class GetCounterValue() extends Request {
  def response(value: Int): Response = CounterValue(value)
}

case class IncrementCounter() extends Request {
  def response(value: Int): Response = CounterValue(value)
}

case class CounterValue(value: Int) extends Response

class TestServer extends GenServer[Int] {

  override def onInit(): Int = {
    0
  }

  override def onRequest(request: Request, counter: Int): OnRequestResult = {
    request match {
      case req: IncrementCounter =>
        val newValue = counter + 1
        ReplyAndSetNewState(req.response(newValue), newValue)
      case req: GetCounterValue =>
        ReplyAndSetNewState(req.response(counter), counter)
    }
  }

  override def onResponse(response: Response, counter: Int): Int = ???
}

case class TestRef(actorRef: ActorRef) extends GenServerRef {
  override def request(request: Request, sender: ActorRef): RequestMessageId = {
    val requestMessage = RequestMessage(request, sender)
    actorRef ! requestMessage
    requestMessage.requestMessageId
  }
}

class TestClient(server: TestRef) extends GenServer[Unit] {

  override def onInit(): Unit = {}

  override def onRequest(request: Request, state: Unit): OnRequestResult = {
    request match {
      case _: IncrementCounter =>
        PostponeResponse(
          server.request(IncrementCounter(), context.self), {
            (response, _) =>
              response match {
                case counterValue: CounterValue =>
                  Reply(counterValue)
              }
          }
        )
      case _: GetCounterValue =>
        PostponeResponse(
          server.request(GetCounterValue(), context.self), {
            (response, _) =>
              response match {
                case counterValue: CounterValue =>
                  Reply(counterValue)
              }
          }
        )
    }
  }

  override def onResponse(response: Response, state: Unit): Unit = ???
}

class GenServerTest
  extends TestKit(ActorSystem("TestActorSystem"))
    with WordSpecLike with Matchers with MockFactory {

  private val server = system.actorOf(Props(classOf[TestServer]))

  private val serverRef = TestRef(server)

  private val client = system.actorOf(Props(classOf[TestClient], serverRef))

  private val clientRef = TestRef(client)

  "A client" should {

    "return server counter value" in {

      val requestMessageId1 = serverRef.request(IncrementCounter(), testActor)

      receiveOne(FiniteDuration(5, TimeUnit.SECONDS)) match {
        case ResponseMessage(reqMsgId, response) =>
          reqMsgId.getId should ===(requestMessageId1.getId)
          response.isInstanceOf[CounterValue] should ===(true)
          val counterValue = response.asInstanceOf[CounterValue]
          counterValue.value should ===(1)
      }

      val requestMessageId2 = clientRef.request(GetCounterValue(), testActor)

      receiveOne(FiniteDuration(5, TimeUnit.SECONDS)) match {
        case ResponseMessage(reqMsgId, response) =>
          reqMsgId.getId should ===(requestMessageId2.getId)
          response.isInstanceOf[CounterValue] should ===(true)
          val counterValue = response.asInstanceOf[CounterValue]
          counterValue.value should ===(1)
      }

    }

  }

}
