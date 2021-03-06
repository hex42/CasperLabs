package io.casperlabs.comm.transport

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.protocol.routing.Protocol
import monix.execution.Callback

trait ServerMessage
// TODO rename to AksMesage and TellMesssage
final case class Ask(msg: Protocol, handle: SenderHandle) extends ServerMessage
final case class Tell(msg: Protocol)                      extends ServerMessage
final case class StreamMessage(
    sender: Node,
    typeId: String,
    path: Path,
    compressed: Boolean,
    contentLength: Int
) extends ServerMessage

trait SenderHandle {
  def reply(msg: CommunicationResponse): Unit
  def failWith(e: Throwable): Unit
  def complete: Boolean
}

final class Reply(callback: Callback[Throwable, CommunicationResponse]) extends SenderHandle {
  // contract: the callback can be called only once
  private val called = new AtomicBoolean(false)

  def reply(msg: CommunicationResponse): Unit =
    if (!called.getAndSet(true)) {
      callback.onSuccess(msg)
    }

  def failWith(e: Throwable): Unit =
    if (!called.getAndSet(true)) {
      callback.onError(e)
    }

  def complete: Boolean = called.get()
}

object Reply {
  def apply(callback: Callback[Throwable, CommunicationResponse]): Reply = new Reply(callback)
}
