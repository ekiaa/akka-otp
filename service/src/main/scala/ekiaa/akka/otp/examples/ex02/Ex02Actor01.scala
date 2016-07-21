package ekiaa.akka.otp.examples.ex02

import akka.actor.{ActorRef, Actor}
import com.typesafe.scalalogging.StrictLogging

trait IEx02Actor01 {

  this: Actor =>

  import Ex02Actor01._

  final def sendMsgToActor01(actorRef: ActorRef, msg: Any) = {
    actorRef ! MsgForActor01(msg)
  }

}

object Ex02Actor01 {

  final case class MsgForActor01(msg: Any)

  final case class DoTest(actorRef: ActorRef)

}

class Ex02Actor01 extends Actor with IEx02Actor02 with StrictLogging {

  import Ex02Actor01._

  def receive = {
    case DoTest(actorRef) =>
      val msg = "ping"
      logger.debug(s"Ex02Actor01 $self send message to Ex02Actor02 $actorRef: $msg")
      sendMsgToActor02(actorRef, msg)
    case MsgForActor01(msg) =>
      logger.debug(s"Ex02Actor01 receive from ${sender()} message: $msg")
    case msg =>
      logger.debug(s"Ex02Actor01 receive from ${sender()} unknown message: $msg")
  }

}
