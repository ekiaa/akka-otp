package ekiaa.akka.otp.examples.transactional_actor

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.typesafe.scalalogging.StrictLogging

class TransactionalActorExample01(id: String) extends PersistentActor with StrictLogging {
  import TransactionalActorExample01._

  override def persistenceId: String = s"TransactionalActor-$id"

  private var persistedState: PersistedState = PersistedState.empty
  private var transientState: TransientState = _
  private var operationState: OperationState = _

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot: PersistedState) =>
      persistedState = snapshot
    case event: Event =>
      persistedState = persistedState.applyEvent(event)
    case RecoveryCompleted =>
      transientState = TransientState.fromPersistedState(persistedState)
      context.become(receiveTransient)
      //TODO Инициализировать зполнение промежуточного состояния
  }

  def receiveTransient: Receive = {
    case _: Command =>
      stash()
    case _ =>
      //TODO Происходит заполнение промежуточного состояния
      //TODO По окончанию, инициализировать оперативное состояние
      operationState = OperationState.fromTransientState(transientState)
      context.become(receiveCommand)
      unstashAll()
  }

  override def receiveCommand: Receive = {
    case _: Command =>

  }

}

object TransactionalActorExample01 {

  trait Command

  trait Event

  trait State

  trait PersistedState extends State {
    def applyEvent(event: Event): PersistedState
  }

  object PersistedState {
    def empty: PersistedState = new PersistedState {
      override def applyEvent(event: Event): PersistedState = ???
    }
  }

  trait TransientState extends State

  object TransientState {
    def fromPersistedState(persistedState: PersistedState): TransientState = ???
  }

  trait OperationState extends State

  object OperationState {
    def fromTransientState(transientState: TransientState): OperationState = ???
  }

}
