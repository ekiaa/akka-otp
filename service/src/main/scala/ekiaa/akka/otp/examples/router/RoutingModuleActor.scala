package ekiaa.akka.otp.examples.router

import java.util.UUID

import akka.pattern
import akka.pattern.{pipe, ask}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import com.typesafe.scalalogging.StrictLogging

import scala.collection.mutable
import scala.concurrent.Future

class RoutingModuleActor(accountId: String,
                         conversationRepository: ConversationRepository
                        ) extends PersistentActor with StrictLogging {
  import RoutingModuleActor._

  private val queueRepository = new QueueRepository
  private val queueFactory = new QueueFactory

  override def persistenceId: String = s"Router-$accountId"

  object RecoveringState {
    private var recoveringState: PersistedState =
      PersistedState(
        waitedProcessingCids = Seq.empty,
        queueIdByGroupId = Map.empty,
        queueIdByEmployeeId = Map.empty,
        queues = Map.empty
      )
    def addCidToWaitedProcessingList(cid: String): Unit =
      recoveringState = recoveringState.copy(
        waitedProcessingCids = recoveringState.waitedProcessingCids :+ cid
      )
  }

  override def receiveRecover: Receive = {
    case ConversationRoutingRequested(cid) =>
      RecoveringState.addCidToWaitedProcessingList(cid)

    case RecoveryCompleted =>
      context.become(idle(State()))
  }

  override def receiveCommand: Receive = ???

  def idle(state: State): Receive = {
    case RouteConversation(cid) =>
      persist(ConversationRoutingRequested(cid)) { _ =>
        processNextRoutingRequest(state.copy(waitedCids = state.waitedCids :+ cid))
      }
  }

  def conversationRequested(cid: String, state: State): Receive = {
    case conversation: Conversation if conversation.cid == cid =>
      val conv =
        Conversation(
          cid = cid,
          groupId = conversation.selectedGroupId,
          commType = conversation.initialCommunicationType
        )
      pipe (self ask GetQueueByGroupId(conv.groupId)) to self
      context.become(queueRequested(conv, state))
    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ => }
  }

  def queueRequested(conv: Conversation, state: State): Receive = {
    case GetQueueByGroupId(groupId) if conv.groupId == groupId =>
      state.queueByGroupId.get(groupId) match {
        case Some(queueId) =>
          val queue = queueRepository.get(queueId).getOrElse(queueFactory.create(queueId))
          sender() ! queue
        case None =>
          val queueId = UUID.randomUUID().toString
          persist(CreatedQueueForGroup(groupId, queueId)) { _ =>
            val queue = queueFactory.create(queueId)
            queueRepository.save(queue)
            sender() ! queue
          }
      }
    case queue: Queue =>

    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ => }
  }

  def processNextRoutingRequest(state: State): Unit = {
    state.waitedCids.headOption match {
      case Some(cid) =>
        pipe(conversationRepository.getConversation(cid)) to self
        context.become(conversationRequested(cid, state.copy(waitedCids = state.waitedCids.tail)))
      case None =>
        context.become(idle(state))
    }
  }

  def obtainQueueForGroupId(conv: Conversation, state: State): Unit = {
    state.queueByGroupId.get(conv.groupId) match {
      case Some(queueId) =>
        val queue = queueRepository.get(queueId).getOrElse(createQueue(queueId))
        enqueueConversation(queue, conv, state)
      case None =>
        val queueId = UUID.randomUUID().toString
        persist(CreatedQueueForGroup(conv.groupId, queueId)) { _ =>
          val queue = createQueue(queueId)
          enqueueConversation(queue, conv,
            state.copy(queueByGroupId = state.queueByGroupId + (conv.groupId -> queueId))
          )
        }
    }
  }

  def enqueueConversation(queue: Queue, conv: Conversation, state: State): Unit = {
    persist(ConversationRouted(conv, queue.id)) { _ =>
      queue.addConv(conv)
      queueRepository.save(queue)
      tryAssignEmployee(queue, conv, state)
    }
  }

  def tryAssignEmployee(queue: Queue, conv: Conversation, state: State): Unit = {

  }

  def createQueue(queueId: String): Queue = {
    val queue = queueFactory.create(queueId)
    queueRepository.save(queue)
    queue
  }

}

object RoutingModuleActor {

  case class PersistedConversation(cid: String,
                                   initialCommunicationType: CommunicationType,
                                   selectedGroup: String,
                                   employeeId: Option[String],
                                   voiceOperators: Set[String])

  case class PersistedQueue(conversations: Seq[PersistedConversation])

  case class PersistedState(waitedProcessingCids: Seq[String],
                            queueIdByGroupId: Map[String, String],
                            queueIdByEmployeeId: Map[String, String],
                            queues: Map[String, PersistedQueue])

  case class State(waitedProcessingCids: Seq[String],
                   queueIdByGroupId: Map[String, String],
                   queueIdByEmployeeId: Map[String, String],
                   conversationsByQueueId: Map[String, Seq[Conversation]],
                   employeesByGroupId: Map[String, Seq[String]])

  trait Event
  case class ConversationRoutingRequested(cid: String) extends Event
  case class CreatedQueueForGroup(queueId: String, qroupId: String) extends Event
  case class ConversationRouted(conv: Conversation, queueId: String) extends Event

  trait Command
  case class GetQueueByGroupId(groupId: String) extends Command

  case class Conversation(cid: String, groupId: String, commType: CommunicationType)

  class Queue(val id: String,
              private val convMap: mutable.Map[String, Conversation]) {
    def addConv(conv: Conversation): Unit = {
      convMap.put(conv.cid, conv)
    }
    def removeConv(cid: String): Unit = {
      convMap.remove(cid)
    }
  }

  class QueueRepository() {
    private val queues: mutable.Map[String, Queue] = mutable.HashMap.empty
    def get(queueId: String): Option[Queue] = {
      queues.get(queueId)
    }
    def save(queue: Queue): Unit = {
      queues.put(queue.id, queue)
    }
  }

  class QueueFactory {
    def create(queueId: String): Queue = {
      new Queue(id = queueId, convMap = mutable.HashMap.empty)
    }
    def create(queueId: String, convs: Seq[Conversation]): Queue = {
      val convMap = mutable.HashMap.empty[String, Conversation]
      convs.foreach(c => convMap.put(c.cid, c))
      new Queue(id = queueId, convMap = convMap)
    }
  }

}

case class RouteConversation(cid: String)

trait CommunicationType

trait ConversationEvent {
  def cid: String
  def initialCommunicationType: CommunicationType
  def selectedGroupId: String
}

trait ConversationRepository {
  def getConversation(cid: String): Future[ConversationEvent]
}
