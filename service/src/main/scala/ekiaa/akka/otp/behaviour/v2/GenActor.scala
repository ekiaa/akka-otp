package ekiaa.akka.otp.behaviour.v2

import java.util.UUID

import akka.actor.{Actor, ActorRef}
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.HashMap

trait Result

case class Reply[State](response: Response, newState: State = null) extends Result

case class Postpone[State](requestPrepack: RequestPrepack, callback: (Response, State) => Result) extends Result

case class Await[State](requestPrepack: RequestPrepack, callback: (Response, State) => Result) extends Result

trait Request

case class RequestPrepack(request: Request, sendTo: ActorRef)

case class RequestMessage(request: Request, sendTo: ActorRef, replyTo: ActorRef, id: String = UUID.randomUUID().toString)

trait Response

case class ResponseMessage(response: Response, id: String)

trait Module[State] {

  def init: State

  def handleRequest(request: Request, state: State): Result

}

class GenActor[State](module: Module[State]) extends Actor with StrictLogging {

  type IncomingRequestId = String
  type OutgoingRequestId = String
  type Callback = (Response, State) => Result

  private var incomingRequests: Map[IncomingRequestId, RequestMessage] = HashMap.empty
  private var outgoingRequests: Map[OutgoingRequestId, (IncomingRequestId, Callback)] = HashMap.empty

  private var state: State = _

  override def preStart(): Unit = {
    state = module.init
    require(state != null, s"GenActor[$self]: State should be initialized")
  }

  override def receive: Receive = {

    case incomingRequest: RequestMessage =>
      module.handleRequest(incomingRequest.request, state) match {
        case Reply(response: Response, newState) =>
          Option(newState).foreach(ns => state = ns.asInstanceOf[State])
          incomingRequest.replyTo ! ResponseMessage(response, incomingRequest.id)

        case Postpone(requestPrepack, callback) =>
          incomingRequests += (incomingRequest.id -> incomingRequest)
          val outgouingRequest = RequestMessage(requestPrepack.request, requestPrepack.sendTo, replyTo = self)
          outgoingRequests += (outgouingRequest.id -> (incomingRequest.id, callback.asInstanceOf[Callback]))
          requestPrepack.sendTo ! outgouingRequest
      }

    case responseMessage: ResponseMessage =>
      val (incomingRequestId, callback) = outgoingRequests(responseMessage.id)
      outgoingRequests -= responseMessage.id
      callback(responseMessage.response, state) match {
        case Reply(response: Response, newState) =>
          Option(newState).foreach(ns => state = ns.asInstanceOf[State])
          val incomingRequest = incomingRequests(incomingRequestId)
          incomingRequest.replyTo ! ResponseMessage(response, incomingRequest.id)
          incomingRequests -= incomingRequestId

        case Postpone(requestPrepack, newCallback) =>
          val outgouingRequest = RequestMessage(requestPrepack.request, requestPrepack.sendTo, replyTo = self)
          outgoingRequests += (outgouingRequest.id -> (incomingRequestId, newCallback.asInstanceOf[Callback]))
          requestPrepack.sendTo ! outgouingRequest
      }

    case unknown =>
      logger.warn(s"GenActor[$self]: Received unknown message[$unknown]")

  }

}
