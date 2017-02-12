package ekiaa.akka.otp.examples.ex03

import akka.actor.{ActorRef, Props, UnboundedStash}
import akka.persistence.PersistentActor
import com.typesafe.scalalogging.StrictLogging
import ekiaa.akka.otp.examples.ex03.Ex03._

class Ex03Actor02(id: String) extends PersistentActor with UnboundedStash with StrictLogging {

  import Ex03Actor02._

  logger.debug(log("Created"))

  override def persistenceId: String = Ex03.actor02PersistenceId(id)

  private var state: Ex03.State = State(count = 0)

  private var transientStateInProcessing: Option[TransientState] = None

  private var processing: Boolean = false

  implicit def getActor(persistenceId: String): ActorRef = Ex03.getActor(persistenceId)

  private var lastAction: Option[Reaction] = None

  override def receiveRecover: Receive = {
    case incomingRequest: IncomingRequest =>
      lastAction = Some(handleIncomingRequest(incomingRequest.request, state))
      processing = true
    case outgoingRequest: OutgoingRequest =>
      lastAction = None
      transientStateInProcessing = Some(outgoingRequest.transientState)
    case incomingResponse: IncomingResponse =>
      lastAction = Some(handleIncomingResponse(incomingResponse.response, transientStateInProcessing.get))
    case outgoingResponse: OutgoingResponse =>
      lastAction = None
      transientStateInProcessing = None
      state = outgoingResponse.state
      processing = false
  }

  override def receiveCommand: Receive = {
    case request: Request if !processing =>
      processing = true
      val domainEvent = IncomingRequest(request)
      persist(domainEvent) {
        incomingRequest =>
          val reaction = handleIncomingRequest(incomingRequest.request, state)
          handleReaction(reaction)
      }
    case _: Request if processing =>
      stash()
    case response: Response =>
      val domainEvent = IncomingResponse(response)
      persist(domainEvent) {
        incomingResponse =>
          val reaction = handleIncomingResponse(incomingResponse.response, transientStateInProcessing.get)
          handleReaction(reaction)
      }

  }

  private def handleIncomingRequest(request: Request, state: Ex03.State): OnIncomingRequest = {
    (request, state) match {
      case (request01: Request01, actorState: State) =>
        val newValue = request01.newValue
        val newCount = actorState.count + 1
        val nextTransientState = ReceiveRequest01(request01.requesterId, newValue, newCount)
        val request02 = Request02(
          requesterId = persistenceId,
          reactorId = Ex03.actor03PersistenceId(id),
          newValue = newValue,
          count = newCount
        )
        RequestActor(request02, nextTransientState)
    }
  }

  private def handleIncomingResponse(response: Response, transientState: TransientState): OnIncomingResponse = {
    (response, transientState) match {
      case (response02: Response02, ReceiveRequest01(requesterId, newValue, newCount)) =>
        val newAvg = response02.avg
        val nextTransientState = ReceiveResponse02(requesterId, newValue, newCount, newAvg)
        val request03 = Request03(
          requesterId = persistenceId,
          reactorId = Ex03.actor04PersistenceId(id),
          newValue = newValue,
          count = newCount,
          avg = newAvg
        )
        RequestActor(request03, nextTransientState)
      case (response03: Response03, ReceiveResponse02(requesterId, _, newCount, newAvg)) =>
        val newMeanDeviation = response03.meanDeviation
        val response01 = Response01(
          requesterId = requesterId,
          reactorId = persistenceId,
          avg = newAvg,
          meanDeviation = newMeanDeviation
        )
        ResponseToActor(response01, State(newCount))
    }
  }

  private def handleReaction(reaction: Reaction): Unit = {
    reaction match {
      case RequestActor(outgoingRequest, nextTransientState) =>
        val nextDomainEvent = OutgoingRequest(outgoingRequest, nextTransientState)
        persist(nextDomainEvent) {
          outgoingRequest =>
            transientStateInProcessing = Some(outgoingRequest.transientState)
            outgoingRequest.request.reactorId.tell(outgoingRequest.request, self)
        }
      case ResponseToActor(outgoingResponse, newState) =>
        val nextDomainEvent = OutgoingResponse(outgoingResponse, newState)
        persist(nextDomainEvent) {
          outgoingResponse =>
            transientStateInProcessing = None
            state = newState
            outgoingResponse.response.requesterId.tell(outgoingResponse.response, self)
            processing = false
        }
    }
  }

  private def log(message: String): String = s"Ex03Actor02[$id]: $message"

}

object Ex03Actor02 {

  case class State(count: Int) extends Ex03.State

  def props(id: String): Props = Props(classOf[Ex03Actor02], id)

  case object NoTransientState extends TransientState
  case class ReceiveRequest01(requesterId: String, newValue: Int, newCount: Int) extends TransientState
  case class ReceiveResponse02(requesterId: String, newValue: Int, newCount: Int, newAvg: Double) extends TransientState
  case class ReceiveResponse03(requesterId: String, newValue: Int, newCount: Int, newAvg: Double, newMeanDeviation: Double) extends TransientState

  trait DomainEvent
  case class ReceivedRequest01(requesterId: String, newValue: Int) extends DomainEvent

  trait Reaction
  trait OnIncomingRequest extends Reaction
  trait OnIncomingResponse extends Reaction
  case class RequestActor(request: Request, transientState: TransientState) extends OnIncomingRequest with OnIncomingResponse
  case class ResponseToActor(response: Response, state: State) extends OnIncomingRequest with OnIncomingResponse

}
