package ekiaa.akka.otp.examples.ex02

import akka.actor.{ActorRef, Actor}
import com.typesafe.scalalogging.StrictLogging

trait IEx02Actor02 {

  this: Actor =>

  import Ex02Actor02._

  final def sendMsgToActor02(actorRef: ActorRef, msg: Any) = {
    actorRef ! MsgForActor02(msg)
  }

}

object Ex02Actor02 {

  final case class MsgForActor02(msg: Any)

}

class Ex02Actor02 extends Actor with IEx02Actor01 with StrictLogging {

  import Ex02Actor02._

  def receive = {
    case MsgForActor02(msg) =>
      logger.debug(s"Ex02Actor02 receive from ${sender()} message: $msg")
      sendMsgToActor01(sender(), "pong")
    case msg =>
      logger.debug(s"Ex02Actor02 receive from ${sender()} unknown message: $msg")
  }

}
