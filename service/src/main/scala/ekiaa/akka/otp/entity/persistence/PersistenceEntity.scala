package ekiaa.akka.otp.entity.persistence

import java.util.UUID

import akka.actor.UnboundedStash
import akka.persistence.PersistentActor
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.HashMap

trait Entity {

  def id: EntityId

  def handleIncomingRequest(request: Request): Reaction

  def handleIncomingResponse(response: Response): Reaction

}

trait EntityId {

  def className: Class[_]

  def persistenceId: String

}

trait Message {

  val id: String

  val correlationId: String

  val requesterId: EntityId

  val reactorId: EntityId

}

trait Request

trait Response

case class RequestMessage(id: String = UUID.randomUUID().toString,
                          correlationId: String = UUID.randomUUID().toString,
                          requesterId: EntityId,
                          reactorId: EntityId,
                          request: Request
                         ) extends Message

case class ResponseMessage(id: String = UUID.randomUUID().toString,
                           correlationId: String,
                           requesterId: EntityId,
                           reactorId: EntityId,
                           response: Response
                          ) extends Message

trait Reaction { val state: Entity }
case class RequestActor(reactorId: EntityId, request: Request, state: Entity) extends Reaction
case class ResponseToActor(response: Response, state: Entity) extends Reaction
case class Ignore(state: Entity) extends Reaction

trait PersistedEvent
case class IncomingRequest(requestMessage: RequestMessage) extends PersistedEvent
case class OutgoingResponse(responseMessage: ResponseMessage) extends PersistedEvent
case class OutgoingRequest(requestMessage: RequestMessage) extends PersistedEvent
case class IncomingResponse(responseMessage: ResponseMessage) extends PersistedEvent

class PersistenceEntity(entityId: EntityId) extends PersistentActor with StrictLogging {

  var state: Entity = PersistenceEntitySystem.getEntityBuilder.build(entityId, None)

  var inProcessing: Boolean = false

  var lastReaction: Option[Reaction] = None

  var lastOutgoingRequest: Option[OutgoingRequest] = None

  var lastIncomingRequest: Option[RequestMessage] = None

  var lastOutgoingResponses: Map[String, ResponseMessage] = HashMap.empty[String, ResponseMessage]

  override def persistenceId: String = entityId.persistenceId

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

    case unknown =>
      logger.warn(s"Receive unknown message: [$unknown]")

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
        val reaction = state.handleIncomingResponse(incomingResponse.responseMessage.response)
        handleReaction(reaction)
    }
  }

  private def handleReaction(reaction: Reaction): Unit = {

    reaction match {
      case action: RequestActor =>
        state = action.state
        val requestMessage = RequestMessage(
          requesterId = entityId,
          reactorId = action.reactorId,
          request = action.request
        )
        handleOutgoingRequest(requestMessage)

      case action: ResponseToActor =>
        state = action.state
        val requesterId = lastIncomingRequest.get.requesterId
        val correlationId = lastIncomingRequest.get.correlationId
        val responseMessage = ResponseMessage(
          correlationId = correlationId,
          requesterId = requesterId,
          reactorId = entityId,
          response = action.response
        )
        handleOutgoingResponse(responseMessage)

      case action: Ignore =>
        state = action.state

    }
  }

  private def handleOutgoingRequest(requestMessage: RequestMessage) = {
    persist(OutgoingRequest(requestMessage)) {
      outgoingRequest =>
        PersistenceEntitySystem(context.system).sendMessage(outgoingRequest.requestMessage)
    }
  }

  private def handleOutgoingResponse(responseMessage: ResponseMessage) = {
    persist(OutgoingResponse(responseMessage)) {
      outgoingResponse =>
        PersistenceEntitySystem(context.system).sendMessage(outgoingResponse.responseMessage)
        inProcessing = false
        unstashAll()
    }
  }

}
