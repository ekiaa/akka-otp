package ekiaa.akka.otp.examples.ex02

import akka.actor.{Props, ActorSystem}
import com.typesafe.scalalogging.StrictLogging

object Example02 extends App with StrictLogging {

  logger.debug("Starting")

  val system = ActorSystem("Example02")

  val actor01 = system.actorOf(Props(new Ex02Actor01))
  val actor02 = system.actorOf(Props(new Ex02Actor02))

  actor01 ! Ex02Actor01.DoTest(actor02)

  Thread.sleep(5000)

  system.terminate()

}
