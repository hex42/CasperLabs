package io.casperlabs.comm.transport

import java.nio.file._

import cats.implicits._
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.transport.PacketOps._
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.duration._

final case class StreamToPeers(peers: Seq[Node], path: Path, sender: Node)

class StreamObservable(bufferSize: Int, folder: Path)(implicit log: Log[Task], scheduler: Scheduler)
    extends Observable[StreamToPeers] {

  private val subject = buffer.LimitedBufferObservable.dropNew[StreamToPeers](bufferSize)

  def stream(peers: List[Node], blob: Blob): Task[Unit] = {

    val storeBlob: Task[Option[Path]] =
      blob.packet.store[Task](folder) >>= {
        case Right(file) => Task.pure(Some(file))
        case Left(UnableToStorePacket(p, er)) =>
          log.error(s"Could not serialize packet $p. Error message: $er") *> None.pure[Task]
        case Left(er) =>
          log.error(s"Could not serialize packet ${blob.packet}. Error: $er") *> None.pure[Task]
      }

    def push(file: Path): Task[Boolean] =
      Task.delay(subject.pushNext(StreamToPeers(peers, file, blob.sender)))

    def propose(file: Path): Task[Unit] =
      push(file) >>= (_.fold(Task.unit, retry(file)))

    def retry(file: Path): Task[Unit] =
      Task
        .defer(log.warn("Retrying push to client stream") *> propose(file))
        .delayExecution(1.second)

    storeBlob >>= (_.fold(Task.unit)(propose))
  }

  def unsafeSubscribeFn(subscriber: Subscriber[StreamToPeers]): Cancelable = {
    val subscription = subject.subscribe(subscriber)
    () => subscription.cancel()
  }
}
