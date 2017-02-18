package ekiaa.akka.otp.examples.ex03

import akka.actor.{ActorContext, ExtendedActorSystem}
import ekiaa.akka.otp.entity.persistence._

object Ex03PersistenceEntitySystemExtension {

  val extensionFactory: PersistenceEntitySystem.ExtensionFactory = { actorSystem =>
    new Ex03PersistenceEntitySystemExtension(actorSystem)
  }

  val Ex03EntityClass: Class[_] = classOf[Ex03Entity]

  val entityBuilder: EntityBuilder = new Ex03EntityBuilder

}

class Ex03PersistenceEntitySystemExtension(actorSystem: ExtendedActorSystem) extends PersistenceEntitySystemExtension {

  override def sendMessage(message: Message)(implicit context: ActorContext): Unit = ???

}

class Ex03EntityBuilder extends EntityBuilder {

  override def build(entityId: EntityId, entitySnapshot: Option[Entity]): Entity = {
    entityId match {
      case eid: Ex03EntityId =>
        new Ex03Entity(eid)
    }
  }

}