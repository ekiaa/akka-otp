package ekiaa.akka.otp.examples.ex03

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

import akka.actor.{ActorRef, ActorSystem}

object Ex03 {

  trait Request {
    val id: String = UUID.randomUUID().toString
    val requesterId: String
    val reactorId: String
  }
  case class Request01(requesterId: String, reactorId: String, newValue: Int) extends Request
  case class Request02(requesterId: String, reactorId: String, newValue: Int, count: Int) extends Request
  case class Request03(requesterId: String, reactorId: String, newValue: Int, count: Int, avg: Double) extends Request

  trait Response {
    val id: String = UUID.randomUUID().toString
    val requesterId: String
    val reactorId: String
  }
  case class Response01(requesterId: String, reactorId: String, avg: Double, meanDeviation: Double) extends Response
  case class Response02(requesterId: String, reactorId: String, avg: Double) extends Response
  case class Response03(requesterId: String, reactorId: String, meanDeviation: Double) extends Response

  trait DomainEvent
  case class IncomingRequest(request: Request) extends DomainEvent
  case class OutgoingResponse(response: Response, state: State) extends DomainEvent
  case class OutgoingRequest(request: Request, transientState: TransientState) extends DomainEvent
  case class IncomingResponse(response: Response) extends DomainEvent

  trait State

  trait TransientState

  def actor01PersistenceId(id: String): String = s"Ex03Actor01-$id"

  def actor02PersistenceId(id: String): String = s"Ex03Actor02-$id"

  def actor03PersistenceId(id: String): String = s"Ex03Actor03-$id"

  def actor04PersistenceId(id: String): String = s"Ex03Actor04-$id"

  val system: ActorSystem = ActorSystem("Example03")

  private val actorMap: ConcurrentHashMap[String, ActorRef] = new ConcurrentHashMap[String, ActorRef]()

  def getActor(persistenceId: String): ActorRef = {
    actorMap.computeIfAbsent(persistenceId,
      new Function[String, ActorRef] {
        override def apply(pid: String): ActorRef = {
          val props = pid.split("-") match {
            case Array("Ex03Actor01", id) => Ex03Actor01.props(id)
            case Array("Ex03Actor02", id) => Ex03Actor02.props(id)
            case Array("Ex03Actor03", id) => Ex03Actor03.props(id)
            case Array("Ex03Actor04", id) => Ex03Actor04.props(id)
          }
          system.actorOf(props, pid)
        }
      }
    )
  }

}
