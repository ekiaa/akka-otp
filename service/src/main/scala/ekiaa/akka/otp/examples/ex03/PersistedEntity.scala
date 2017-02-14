package ekiaa.akka.otp.examples.ex03

import java.util.UUID

import akka.actor.{ActorRef, UnboundedStash}
import akka.persistence.PersistentActor
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.HashMap

trait Request

trait Response

case class RequestMessage(requesterId: String, reactorId: String, request: Request) {
  val id: String = UUID.randomUUID().toString
  val correlationId: String = UUID.randomUUID().toString
}
case class ResponseMessage(requesterId: String, reactorId: String, response: Response, correlationId: String) {
  val id: String = UUID.randomUUID().toString
}

trait Reaction[State] { val state: State }
case class RequestActor[State](reactorId: String, request: Request, state: State) extends Reaction[State]
case class ResponseToActor[State](response: Response, state: State) extends Reaction[State]

trait PersistedEvent
case class IncomingRequest(requestMessage: RequestMessage) extends PersistedEvent
case class OutgoingResponse(responseMessage: ResponseMessage) extends PersistedEvent
case class OutgoingRequest(requestMessage: RequestMessage) extends PersistedEvent
case class IncomingResponse(responseMessage: ResponseMessage) extends PersistedEvent

trait State {

  def apply(shapshot: Option[State]): State

  def handleIncomingRequest(request: Request): Reaction[State]

  def handleIncomingResponse(response: Response): Reaction[State]

}

class PersistedEntity(id: String, factory: (Option[State]) => State) extends PersistentActor with UnboundedStash with StrictLogging {

  implicit def getActor(persistenceId: String): ActorRef = Ex03.getActor(persistenceId)

  var state: State = factory(None)

  var inProcessing: Boolean = false

  var lastReaction: Option[Reaction[State]] = None

  var lastOutgoingRequest: Option[OutgoingRequest] = None

  var lastIncomingRequest: Option[RequestMessage] = None

  var lastOutgoingResponses: Map[String, Response] = HashMap.empty[String, Response]

  override def persistenceId: String = id

  override def receiveRecover: Receive = {
    case incomingRequest: IncomingRequest =>
      lastIncomingRequest = Some(incomingRequest.requestMessage)
      val reaction = state.handleIncomingRequest(incomingRequest.requestMessage.request)
      lastReaction = Some(reaction)
      state = reaction.state
      inProcessing = true
    case outgoingRequest: OutgoingRequest =>
      lastReaction = None
      lastOutgoingRequest = Some(outgoingRequest)
    case incomingResponse: IncomingResponse =>
      lastOutgoingRequest = None
      val reaction = state.handleIncomingResponse(incomingResponse.responseMessage.response)
      lastReaction = Some(reaction)
      state = reaction.state
    case outgoingResponse: OutgoingResponse =>
      lastIncomingRequest = None
      lastReaction = None
      inProcessing = false
  }

  override def receiveCommand: Receive = {

    case requestMessage: RequestMessage if !inProcessing =>
      handleIncomingRequest(requestMessage)

    case _: RequestMessage if inProcessing =>
      stash()

    case responseMessage: ResponseMessage if inProcessing =>
      handleIncomingResponse(responseMessage)

    case responseMessage: ResponseMessage =>
      logger.warn(s"Receive responseMessage[$responseMessage] when not inProcessing")

  }

  private def handleIncomingRequest(requestMessage: RequestMessage) = {
    inProcessing = true
    persist(IncomingRequest(requestMessage)) {
      incomingRequest =>
        lastIncomingRequest = Some(incomingRequest.requestMessage)
        val reaction = state.handleIncomingRequest(incomingRequest.requestMessage.request)
        handleReaction(reaction)
    }
  }

  private def handleIncomingResponse(responseMessage: ResponseMessage) = {
    persist(IncomingResponse(responseMessage)) {
      incomingResponse =>
        val reaction = state.handleIncomingResponse(responseMessage.response)
        handleReaction(reaction)
    }
  }

  private def handleReaction(reaction: Reaction[State]): Unit = {
    reaction match {
      case RequestActor(reactorId, request, newState) =>
        state = newState
        val requestMessage = RequestMessage(persistenceId, reactorId, request)
        handleOutgoingRequest(requestMessage)
      case ResponseToActor(response, newState) =>
        state = newState
        val requesterId = lastIncomingRequest.get.requesterId
        val correlationId = lastIncomingRequest.get.correlationId
        val responseMessage = ResponseMessage(requesterId, persistenceId, response, correlationId)
        handleOutgoingResponse(responseMessage)
    }
  }

  private def handleOutgoingRequest(requestMessage: RequestMessage) = {
    persist(OutgoingRequest(requestMessage)) {
      outgoingRequest =>
        outgoingRequest.requestMessage.reactorId.tell(outgoingRequest.requestMessage, self)
    }
  }

  private def handleOutgoingResponse(responseMessage: ResponseMessage) = {
    persist(OutgoingResponse(responseMessage)) {
      outgoingResponse =>
        outgoingResponse.responseMessage.requesterId.tell(outgoingResponse.responseMessage, self)
        inProcessing = false
    }
  }

}
