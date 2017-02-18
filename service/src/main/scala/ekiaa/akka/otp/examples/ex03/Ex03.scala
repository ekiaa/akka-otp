package ekiaa.akka.otp.examples.ex03

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import ekiaa.akka.otp.entity.persistence.{PersistenceEntity, PersistenceEntitySystem}

object Ex03 extends App {

  PersistenceEntitySystem.registerExtensionFactory(
    Ex03PersistenceEntitySystemExtension.extensionFactory
  )
  PersistenceEntitySystem.registerEntityBuilder(
    Ex03PersistenceEntitySystemExtension.entityBuilder
  )

  val actorSystem = ActorSystem("Ex03ActorSystem",
    ConfigFactory.parseString(
      """akka.extensions = ["ekiaa.akka.otp.entity.persistence.PersistenceEntitySystem"]
        |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
        |akka.persistence.journal.leveldb.dir = "target/journal"
        |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
        |akka.persistence.snapshot-store.local.dir = "target/snapshots"
      """.stripMargin
    )
  )

  val id = UUID.randomUUID().toString

  val entityId = Ex03EntityId(id)

  val actorRef = actorSystem.actorOf(
    props = Props(classOf[PersistenceEntity], entityId),
    name = entityId.persistenceId
  )

  actorRef.tell("Message", null)

}
