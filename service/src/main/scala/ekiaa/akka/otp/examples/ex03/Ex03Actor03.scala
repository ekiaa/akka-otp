package ekiaa.akka.otp.examples.ex03

import akka.actor.Props

class Ex03Actor03 {

}

object Ex03Actor03 {

  def props(id: String): Props = Props(classOf[Ex03Actor03], id)

}
