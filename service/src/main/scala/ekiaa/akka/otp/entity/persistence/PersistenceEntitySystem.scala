package ekiaa.akka.otp.entity.persistence

import akka.actor.{ActorContext, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}

object PersistenceEntitySystem extends ExtensionId[PersistenceEntitySystemExtension] with ExtensionIdProvider {

  type ExtensionFactory = (ExtendedActorSystem) => PersistenceEntitySystemExtension

  private var entityBuilder: Option[EntityBuilder] = None

  def registerEntityBuilder(builder: EntityBuilder): Unit = {
    require(entityBuilder.isEmpty, "PersistenceEntitySystem: entityBuilder should not be registered when registerEntityBuilder invoked")
    entityBuilder = Some(builder)
  }

  def getEntityBuilder: EntityBuilder = {
    require(entityBuilder.isDefined, "PersistenceEntitySystem: entityBuilder should be registered when getEntityBuilder invoked")
    entityBuilder.get
  }

  private var extensionFactory: Option[ExtensionFactory] = None

  def registerExtensionFactory(factory: ExtensionFactory): Unit = {
    require(extensionFactory.isEmpty, "PersistenceEntitySystem: extensionFactory should not be registered when registerExtensionFactory invoked")
    extensionFactory = Some(factory)
  }

  override def createExtension(system: ExtendedActorSystem): PersistenceEntitySystemExtension = {
    require(extensionFactory.isDefined, "PersistenceEntitySystem: extensionFactory should not be null when createExtension invoked")
    extensionFactory.get(system)
  }

  override def lookup(): ExtensionId[_ <: Extension] = PersistenceEntitySystem

  override def get(system: ActorSystem): PersistenceEntitySystemExtension = super.get(system)

}

trait PersistenceEntitySystemExtension extends Extension {

  def sendMessage(message: Message)(implicit context: ActorContext): Unit

}

trait EntityBuilder {

  def build(entityId: EntityId, entitySnapshot: Option[Entity]): Entity

}
