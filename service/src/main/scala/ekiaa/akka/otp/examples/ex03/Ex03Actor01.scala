package ekiaa.akka.otp.examples.ex03

import akka.actor.Props

class Ex03Actor01 {

}

object Ex03Actor01 {

  def props(id: String): Props = Props(classOf[Ex03Actor01], id)

}
