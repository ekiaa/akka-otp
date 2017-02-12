package ekiaa.akka.otp.behaviour.v1

import java.util.UUID

import akka.actor.{Actor, ActorRef}

import scala.collection.immutable.HashMap

trait RequestMessageId {
  def getId: String
}

class RequestMessageUUID extends RequestMessageId {
  private val id = UUID.randomUUID().toString
  override def getId: String = id
}

trait GenServerRef {
  def request(request: Request, sender: ActorRef): RequestMessageId
}

trait Request

case class RequestMessage(request: Request, sender: ActorRef) {
  val requestMessageId: RequestMessageUUID = new RequestMessageUUID
  def replyTo(response: Response): Unit = {
    sender ! ResponseMessage(requestMessageId, response)
  }
}

trait Response

case class ResponseMessage(requestMessageId: RequestMessageId, response: Response)

trait GenServer[State] extends Actor {

  trait OnRequestResult

  case class Reply(response: Response) extends OnRequestResult

  case class ReplyAndSetNewState(response: Response, newState: State) extends OnRequestResult

  type DelayedFunction = (Response, State) => OnRequestResult

  case class PostponeResponse(requestMessageId: RequestMessageId, delayedFunction: DelayedFunction) extends OnRequestResult

  private var state: State = _

  private var delayedFunctions: Map[String, (RequestMessage, DelayedFunction)] = HashMap.empty

  def onInit(): State

  def onRequest(request: Request, state: State): OnRequestResult

  def onResponse(response: Response, state: State): State

  override def preStart(): Unit = {
    state = onInit()
  }

  override def receive: Receive = {

    case requestMessage: RequestMessage =>
      processMessage(
        requestMessage,
        onRequest(requestMessage.request, state)
      )

    case responseMessage: ResponseMessage =>
      delayedFunctions.get(responseMessage.requestMessageId.getId) match {
        case Some((requestMessage, delayedFunction)) =>
          delayedFunctions -= responseMessage.requestMessageId.getId
          processMessage(
            requestMessage,
            delayedFunction(responseMessage.response, state)
          )
        case None =>
          val newState = onResponse(responseMessage.response, state)
          state = newState
      }
  }

  private def processMessage(requestMessage: RequestMessage, result: OnRequestResult): Unit = {
    result match {
      case reply: Reply =>
        requestMessage.replyTo(reply.response)
      case reply: ReplyAndSetNewState =>
        state = reply.newState
        requestMessage.replyTo(reply.response)
      case reply: PostponeResponse =>
        val id = reply.requestMessageId.getId
        delayedFunctions += (id -> (requestMessage, reply.delayedFunction))
    }
  }

}
