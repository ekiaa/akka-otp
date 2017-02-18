package ekiaa.akka.otp.examples.ex03

import com.typesafe.scalalogging.StrictLogging
import ekiaa.akka.otp.entity.persistence._

class Ex03Entity(val id: Ex03EntityId) extends Entity with StrictLogging {

  override def handleIncomingRequest(request: Request): Reaction = {
    request match {

      case unknown =>
        logger.warn(log(s"Receive unknown request: [$request]"))
        Ignore(this)

    }
  }

  override def handleIncomingResponse(response: Response): Reaction = {
    response match {

      case unknown =>
        logger.warn(log(s"Receive unknown response: [$response]"))
        Ignore(this)

    }
  }

  private def log(message: String): String = s"Ex03Entity[$id]: $message"

}

case class Ex03EntityId(id: String) extends EntityId {

  override def className: Class[_] = Ex03PersistenceEntitySystemExtension.Ex03EntityClass

  override def persistenceId: String = s"Ex03EntityId-$id"

}
