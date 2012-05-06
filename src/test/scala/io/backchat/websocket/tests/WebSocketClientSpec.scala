package io.backchat.websocket
package tests

import org.specs2.Specification
import org.specs2.time.NoTimeConversions
import net.liftweb.json.DefaultFormats
import org.specs2.execute.Result
import akka.dispatch.Await
import java.net.{ServerSocket, URI}
import org.specs2.specification.{Step, Fragments}
import akka.util.duration._
import java.util.concurrent.TimeoutException
import akka.testkit._
import akka.actor.ActorSystem
import net.liftweb.json.JsonAST.{JField, JString, JObject}

object WebSocketClientSpec {

  def newServer(port: Int)(implicit wireFormat: WireFormat): WebSocketServer =
    WebSocketServer(ServerInfo("Test Echo Server", listenOn = "127.0.0.1", port = port)) {
      new WebSocketServerClient {
        def receive = {
          case TextMessage(text) ⇒ send(text)
          case JsonMessage(json) => send(json)
        }
      }
    }
}

trait WebSocketClientSupport { self: Specification =>
  implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)
  val serverAddress = {
    val s = new ServerSocket(0);
    try { s.getLocalPort } finally { s.close() }
  }
  val server = WebSocketClientSpec.newServer(serverAddress)


  override def map(fs: => Fragments) =
    Step(server.start) ^ super.map(fs) ^ Step(server.stop)

  type Handler = PartialFunction[(WebSocket, WebSocketInMessage), Any]

  def withWebSocket[T <% Result](handler: Handler)(t: WebSocket => T) = {
    val client = new WebSocket {
      val uri = new URI("ws://127.0.0.1:"+serverAddress.toString+"/")
      override implicit val wireFormat: WireFormat = new JsonProtocolWireFormat()(jsonFormats)
      val settings = WebSocketContext(uri)
      def receive = {
        case m  => handler.lift((this, m))
      }
    }
    Await.ready(client.connect(), 5 seconds)
    try { t(client) } finally { try { Await.ready(client.close(), 2 seconds) } catch { case e => e.printStackTrace() }}
  }

}

class WebSocketClientSpec extends Specification with NoTimeConversions  { def is =
  "A WebSocketClient should" ^
    "connects to server" ! connectsToServer ^
    "exchange json messages with the server" ! pending ^
  end

  implicit val system: ActorSystem = ActorSystem("WebSocketClientSpec")

  def connectsToServer = {
    val latch = TestLatch()
    withWebSocket({
      case (_, Connected) => latch.open()
    }) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
  }

  def exchangesJsonMessages = {
    val latch = TestLatch()
    withWebSocket({
      case (client, Connected) => client send JObject(JField("hello", JString("world")) :: Nil)
    }) { _ => Await.result(latch, 5 seconds) must not(throwA[TimeoutException]) }
  }
}
