package io.casperlabs.casper.util.comm

import cats.data.EitherT
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.LastApprovedBlock.LastApprovedBlock
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.MonadTrans
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.{Blob, TransportLayer}
import io.casperlabs.comm.transport
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockMetadata
import io.casperlabs.p2p.effects.PacketHandler
import io.casperlabs.shared.{Log, LogSource, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object CasperPacketHandler extends CasperPacketHandlerInstances {
  private implicit val logSource: LogSource = LogSource(this.getClass)

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "packet-handler")

  def apply[F[_]](implicit ev: CasperPacketHandler[F]): CasperPacketHandler[F] = ev

  /** Export a base 0 value so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics]: F[Unit] =
    for {
      _ <- Metrics[F].incrementCounter("blocks-received", 0)
      _ <- Metrics[F].incrementCounter("blocks-received-again", 0)
    } yield ()

  def of[F[_]: LastApprovedBlock: Metrics: BlockStore: ConnectionsCell: NodeDiscovery: TransportLayer: ErrorHandler: RPConfAsk: SafetyOracle: Sync: Concurrent: Time: Log: MultiParentCasperRef: BlockDagStorage: ExecutionEngineService](
      conf: CasperConf,
      delay: FiniteDuration,
      executionEngineService: ExecutionEngineService[F],
      toTask: F[_] => Task[_]
  )(implicit scheduler: Scheduler): F[CasperPacketHandler[F]] = {
    val handler: F[CasperPacketHandler[F]] = if (conf.approveGenesis) {
      for {
        _           <- Log[F].info("Starting in approve genesis mode")
        timestamp   <- conf.deployTimestamp.fold(Time[F].currentMillis)(_.pure[F])
        wallets     <- Genesis.getWallets[F](conf.walletsFile)
        bonds       <- Genesis.getBonds[F](conf.genesisPath, conf.bondsFile, conf.numValidators)
        _           <- ExecutionEngineService[F].setBonds(bonds)
        validatorId <- ValidatorIdentity.fromConfig[F](conf)
        bap = new BlockApproverProtocol(
          validatorId.get,
          timestamp,
          bonds,
          wallets,
          conf.minimumBond,
          conf.maximumBond,
          conf.hasFaucet,
          conf.requiredSigs
        )
        gv <- Ref.of[F, CasperPacketHandlerInternal[F]](
               new GenesisValidatorHandler(
                 validatorId.get,
                 conf.shardId,
                 bap
               )
             )
      } yield new CasperPacketHandlerImpl[F](gv)
    } else if (conf.standalone) {
      for {
        _     <- Log[F].info("Starting in create genesis mode")
        bonds <- Genesis.getBonds[F](conf.genesisPath, conf.bondsFile, conf.numValidators)
        _     <- ExecutionEngineService[F].setBonds(bonds)
        genesis <- Genesis[F](
                    conf.walletsFile,
                    conf.minimumBond,
                    conf.maximumBond,
                    conf.hasFaucet,
                    conf.shardId,
                    conf.deployTimestamp
                  )
        BlockMsgWithTransform(Some(genesisBlock), genesisTransforms) = genesis
        validatorId                                                  <- ValidatorIdentity.fromConfig[F](conf)
        bondedValidators = genesisBlock.body
          .flatMap(_.state.map(_.bonds.map(_.validator).toSet))
          .getOrElse(Set.empty)
        abp <- ApproveBlockProtocol
                .of[F](
                  genesisBlock,
                  genesisTransforms,
                  bondedValidators,
                  conf.requiredSigs,
                  conf.approveGenesisDuration,
                  conf.approveGenesisInterval
                )
                .map(protocol => {
                  toTask(protocol.run()).forkAndForget.runToFuture
                  protocol
                })
        standalone <- Ref.of[F, CasperPacketHandlerInternal[F]](new StandaloneCasperHandler[F](abp))
        _ <- Sync[F].delay {
              val _ = toTask(
                StandaloneCasperHandler
                  .approveBlockInterval(
                    conf.approveGenesisInterval,
                    conf.shardId,
                    validatorId,
                    standalone
                  )
              ).forkAndForget.runToFuture
              ().pure[F]
            }
      } yield new CasperPacketHandlerImpl[F](standalone)
    } else {
      for {
        _ <- Log[F].info("Starting in default mode")
        // FIXME: The bonds should probably be taken from the approved block, but that's not implemented.
        bonds       <- Genesis.getBonds[F](conf.genesisPath, conf.bondsFile, conf.numValidators)
        validators  <- CasperConf.parseValidatorsFile[F](conf.knownValidatorsFile)
        validatorId <- ValidatorIdentity.fromConfig[F](conf)
        _           <- ExecutionEngineService[F].setBonds(bonds)
        bootstrap <- Ref.of[F, CasperPacketHandlerInternal[F]](
                      new BootstrapCasperHandler(
                        conf.shardId,
                        validatorId,
                        validators
                      )
                    )
        casperPacketHandler = new CasperPacketHandlerImpl[F](bootstrap)
        _ <- Sync[F].delay {
              implicit val ph: PacketHandler[F] = PacketHandler.pf[F](casperPacketHandler.handle)
              val rb                            = CommUtil.requestApprovedBlock[F](delay)
              toTask(rb).forkAndForget.runToFuture
              ().pure[F]
            }
      } yield casperPacketHandler
    }
    establishMetrics[F] *> handler
  }

  trait CasperPacketHandlerInternal[F[_]] {
    def handleBlockMessage(peer: Node, bm: BlockMessage): F[Unit]

    def handleBlockRequest(peer: Node, br: BlockRequest): F[Unit]

    def handleForkChoiceTipRequest(peer: Node, fctr: ForkChoiceTipRequest): F[Unit]

    def handleApprovedBlock(ab: ApprovedBlock): F[Option[MultiParentCasper[F]]]

    def handleApprovedBlockRequest(peer: Node, br: ApprovedBlockRequest): F[Unit]

    def handleUnapprovedBlock(peer: Node, ub: UnapprovedBlock): F[Unit]

    def handleBlockApproval(ba: BlockApproval): F[Unit]

    def handleNoApprovedBlockAvailable(na: NoApprovedBlockAvailable): F[Unit]
  }

  /** Node in this state is a genesis block validator. It will respond only to
    * [[UnapprovedBlock]] messages forwarding the logic of handling this message to
    * instance of [[BlockApproverProtocol]] class.
    *
    * When in this state node can't handle any other message type so it will return `F[None]`
    **/
  private[comm] class GenesisValidatorHandler[F[_]: Sync: Concurrent: ConnectionsCell: NodeDiscovery: TransportLayer: Log: Time: SafetyOracle: ErrorHandler: RPConfAsk: BlockStore: LastApprovedBlock: BlockDagStorage: ExecutionEngineService](
      validatorId: ValidatorIdentity,
      shardId: String,
      blockApprover: BlockApproverProtocol
  ) extends CasperPacketHandlerInternal[F] {
    private val noop: F[Unit] = Applicative[F].unit

    override def handleBlockMessage(peer: Node, bm: BlockMessage): F[Unit] =
      noop
    override def handleBlockRequest(peer: Node, br: BlockRequest): F[Unit] =
      noop
    override def handleForkChoiceTipRequest(peer: Node, fctr: ForkChoiceTipRequest): F[Unit] =
      noop
    override def handleApprovedBlock(
        ab: ApprovedBlock
    ): F[Option[MultiParentCasper[F]]] =
      for {
        _ <- Log[F].info("Received ApprovedBlock message while in GenesisValidatorHandler state.")
        casperO <- onApprovedBlockTransition(
                    ab,
                    Set(ByteString.copyFrom(validatorId.publicKey)),
                    Some(validatorId),
                    shardId
                  )
        _ <- casperO.fold(Log[F].warn("MultiParentCasper instance not created."))(
              _ => Log[F].info("MultiParentCasper instance created.")
            )
      } yield casperO

    override def handleApprovedBlockRequest(
        peer: Node,
        br: ApprovedBlockRequest
    ): F[Unit] =
      sendNoApprovedBlockAvailable(peer, br.identifier)

    override def handleBlockApproval(ba: BlockApproval): F[Unit] =
      noop

    override def handleUnapprovedBlock(peer: Node, ub: UnapprovedBlock): F[Unit] =
      blockApprover.unapprovedBlockPacketHandler(peer, ub)

    override def handleNoApprovedBlockAvailable(na: NoApprovedBlockAvailable): F[Unit] =
      Log[F].info(s"No approved block available on node ${na.nodeIdentifer}")
  }

  /** Node in this state is will send out an [[UnapprovedBlock]] message to all peers
    * and will wait for [[BlockApproval]] messages forwarding handling of those to instance of [[ApproveBlockProtocol]] class.
    * After enough [[BlockApproval]]s has been received it will create an [[ApprovedBlock]] and send it to peers.
    *
    *
    * For all other messages it will return `F[None]`.
    **/
  private[comm] class StandaloneCasperHandler[F[_]: Sync: ConnectionsCell: NodeDiscovery: BlockStore: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: RPConfAsk: LastApprovedBlock](
      approveProtocol: ApproveBlockProtocol[F]
  ) extends CasperPacketHandlerInternal[F] {

    private val noop: F[Unit] = Applicative[F].unit

    override def handleBlockMessage(peer: Node, bm: BlockMessage): F[Unit] =
      noop
    override def handleBlockRequest(peer: Node, br: BlockRequest): F[Unit] =
      noop
    override def handleForkChoiceTipRequest(peer: Node, fctr: ForkChoiceTipRequest): F[Unit] =
      noop
    override def handleApprovedBlock(
        ab: ApprovedBlock
    ): F[Option[MultiParentCasper[F]]] =
      none[MultiParentCasper[F]].pure[F]
    override def handleApprovedBlockRequest(
        peer: Node,
        br: ApprovedBlockRequest
    ): F[Unit] =
      sendNoApprovedBlockAvailable(peer, br.identifier)

    override def handleUnapprovedBlock(peer: Node, ub: UnapprovedBlock): F[Unit] =
      noop

    override def handleBlockApproval(ba: BlockApproval): F[Unit] =
      approveProtocol.addApproval(ba)

    override def handleNoApprovedBlockAvailable(na: NoApprovedBlockAvailable): F[Unit] =
      Log[F].info(s"No approved block available on node ${na.nodeIdentifer}")
  }

  object StandaloneCasperHandler {
    def approveBlockInterval[F[_]: Concurrent: ConnectionsCell: NodeDiscovery: BlockStore: TransportLayer: Log: Metrics: Time: ErrorHandler: SafetyOracle: RPConfAsk: LastApprovedBlock: MultiParentCasperRef: BlockDagStorage: ExecutionEngineService](
        interval: FiniteDuration,
        shardId: String,
        validatorId: Option[ValidatorIdentity],
        capserHandlerInternal: Ref[F, CasperPacketHandlerInternal[F]]
    ): F[Unit] =
      for {
        _                  <- Time[F].sleep(interval)
        lastApprovedBlockO <- LastApprovedBlock[F].get
        cont <- lastApprovedBlockO match {
                 case None =>
                   approveBlockInterval[F](
                     interval,
                     shardId,
                     validatorId,
                     capserHandlerInternal
                   )
                 case Some(ApprovedBlockWithTransforms(approvedBlock, transforms)) =>
                   val blockMessage = approvedBlock.candidate.flatMap(_.block).get
                   for {
                     _ <- BlockStore[F].put(
                           blockMessage.blockHash,
                           blockMessage,
                           transforms
                         )
                     casper <- MultiParentCasper.hashSetCasper[F](
                                validatorId,
                                blockMessage,
                                shardId
                              )
                     _   <- MultiParentCasperRef[F].set(casper)
                     _   <- Log[F].info("Making a transition to ApprovedBlockRecievedHandler state.")
                     abh = new ApprovedBlockReceivedHandler[F](casper, approvedBlock)
                     _   <- capserHandlerInternal.set(abh)
                     _   <- CommUtil.sendForkChoiceTipRequest[F]
                   } yield ()
               }
      } yield cont
  }

  /** Node in this state will query peers in the network with [[ApprovedBlockRequest]] message
    * and will wait for the [[ApprovedBlock]] message to arrive. Until then  it will respond with
    * `F[None]` to all other message types.
    **/
  private[comm] class BootstrapCasperHandler[F[_]: Concurrent: ConnectionsCell: NodeDiscovery: BlockStore: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: RPConfAsk: LastApprovedBlock: BlockDagStorage: ExecutionEngineService](
      shardId: String,
      validatorId: Option[ValidatorIdentity],
      validators: Set[ByteString]
  ) extends CasperPacketHandlerInternal[F] {
    private val noop: F[Unit] = Applicative[F].unit

    override def handleBlockMessage(peer: Node, bm: BlockMessage): F[Unit] =
      noop
    override def handleBlockRequest(peer: Node, br: BlockRequest): F[Unit] =
      noop
    override def handleForkChoiceTipRequest(peer: Node, fctr: ForkChoiceTipRequest): F[Unit] =
      noop
    override def handleApprovedBlockRequest(
        peer: Node,
        br: ApprovedBlockRequest
    ): F[Unit] =
      sendNoApprovedBlockAvailable(peer, br.identifier)

    override def handleUnapprovedBlock(peer: Node, ub: UnapprovedBlock): F[Unit] =
      noop
    override def handleBlockApproval(ba: BlockApproval): F[Unit] = noop

    override def handleApprovedBlock(
        ab: ApprovedBlock
    ): F[Option[MultiParentCasper[F]]] =
      onApprovedBlockTransition(
        ab,
        validators,
        validatorId,
        shardId
      )

    override def handleNoApprovedBlockAvailable(na: NoApprovedBlockAvailable): F[Unit] =
      Log[F].info(s"No approved block available on node ${na.nodeIdentifer}")

  }

  /** Node in this state has already received at least one [[ApprovedBlock]] and it has created an instance
    * of [[MultiParentCasper]].
    *
    * In the future it will be possible to create checkpoint with new [[ApprovedBlock]].
    **/
  class ApprovedBlockReceivedHandler[F[_]: RPConfAsk: BlockStore: Monad: ConnectionsCell: TransportLayer: Log: Metrics: Time: ErrorHandler](
      private val casper: MultiParentCasper[F],
      approvedBlock: ApprovedBlock
  ) extends CasperPacketHandlerInternal[F] {

    implicit val _casper = casper

    private val noop: F[Unit] = Applicative[F].unit

    // Possible optimization in the future
    // TODO: accept update to approved block (this will be needed for checkpointing)
    override def handleApprovedBlock(
        ab: ApprovedBlock
    ): F[Option[MultiParentCasper[F]]] =
      none[MultiParentCasper[F]].pure[F]

    override def handleUnapprovedBlock(peer: Node, ub: UnapprovedBlock): F[Unit] =
      noop

    override def handleBlockApproval(b: BlockApproval): F[Unit] =
      noop

    override def handleBlockMessage(peer: Node, b: BlockMessage): F[Unit] =
      for {
        _          <- Metrics[F].incrementCounter("blocks-received")
        isOldBlock <- MultiParentCasper[F].contains(b)
        _ <- if (isOldBlock) {
              for {
                _ <- Log[F].info(s"Received block ${PrettyPrinter.buildString(b.blockHash)} again.")
                _ <- Metrics[F].incrementCounter("blocks-received-again")
              } yield ()
            } else {
              for {
                _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(b)}.")
                _ <- MultiParentCasper[F].addBlock(b, handleDoppelganger[F](peer, _, _))
              } yield ()
            }
      } yield ()

    override def handleBlockRequest(peer: Node, br: BlockRequest): F[Unit] =
      for {
        local      <- RPConfAsk[F].reader(_.local)
        block      <- BlockStore[F].getBlockMessage(br.hash)
        serialized = block.map(_.toByteString)
        maybeMsg = serialized.map(
          serializedMessage => Blob(local, Packet(transport.BlockMessage.id, serializedMessage))
        )
        _        <- maybeMsg.traverse(msg => TransportLayer[F].stream(Seq(peer), msg))
        hash     = PrettyPrinter.buildString(br.hash)
        logIntro = s"Received request for block $hash from $peer."
        _ <- block match {
              case None    => Log[F].info(logIntro + "No response given since block not found.")
              case Some(_) => Log[F].info(logIntro + "Response sent.")
            }
      } yield ()

    override def handleForkChoiceTipRequest(
        peer: Node,
        fctr: ForkChoiceTipRequest
    ): F[Unit] =
      for {
        _     <- Log[F].info(s"Received ForkChoiceTipRequest from $peer")
        tip   <- MultiParentCasper.forkChoiceTip
        local <- RPConfAsk[F].reader(_.local)
        msg   = Blob(local, Packet(transport.BlockMessage.id, tip.toByteString))
        _     <- TransportLayer[F].stream(Seq(peer), msg)
        _     <- Log[F].info(s"Sending Block ${tip.blockHash} to $peer")
      } yield ()

    override def handleApprovedBlockRequest(
        peer: Node,
        br: ApprovedBlockRequest
    ): F[Unit] =
      for {
        local <- RPConfAsk[F].reader(_.local)
        _     <- Log[F].info(s"Received ApprovedBlockRequest from $peer")
        msg   = Blob(local, Packet(transport.ApprovedBlock.id, approvedBlock.toByteString))
        _     <- TransportLayer[F].stream(Seq(peer), msg)
        _     <- Log[F].info(s"Sending ApprovedBlock to $peer")
      } yield ()

    override def handleNoApprovedBlockAvailable(na: NoApprovedBlockAvailable): F[Unit] =
      Log[F].info(s"No approved block available on node ${na.nodeIdentifer}")
  }

  class CasperPacketHandlerImpl[F[_]: Monad: RPConfAsk: BlockStore: ConnectionsCell: TransportLayer: Log: Metrics: Time: ErrorHandler: LastApprovedBlock: MultiParentCasperRef](
      private val cphI: Ref[F, CasperPacketHandlerInternal[F]]
  ) extends CasperPacketHandler[F] {

    override def handle(peer: Node): PartialFunction[Packet, F[Unit]] =
      Function
        .unlift(
          (p: Packet) =>
            packetToBlockRequest(p) orElse
              packetToForkChoiceTipRequest(p) orElse
              packetToApprovedBlock(p) orElse
              packetToApprovedBlockRequest(p) orElse
              packetToBlockMessage(p) orElse
              packetToBlockApproval(p) orElse
              packetToUnapprovedBlock(p) orElse
              packetToNoApprovedBlockAvailable(p)
        )
        .andThen {
          case br: BlockRequest =>
            for {
              cph <- cphI.get
              res <- cph.handleBlockRequest(peer, br)
            } yield res

          case fctr: ForkChoiceTipRequest =>
            for {
              cph <- cphI.get
              res <- cph.handleForkChoiceTipRequest(peer, fctr)
            } yield res

          case ab: ApprovedBlock =>
            for {
              cph    <- cphI.get
              casper <- cph.handleApprovedBlock(ab)
              _ <- casper match {
                    case None => ().pure[F]
                    case Some(casperInstance) =>
                      for {
                        _ <- MultiParentCasperRef[F].set(casperInstance)
                        _ <- Log[F].info(
                              "Making a transition to ApprovedBlockRecievedHandler state."
                            )
                        abr = new ApprovedBlockReceivedHandler(casperInstance, ab)
                        _   <- cphI.set(abr)
                        _   <- CommUtil.sendForkChoiceTipRequest[F]
                      } yield ()

                  }
            } yield ()

          case abr: ApprovedBlockRequest =>
            for {
              cph <- cphI.get
              res <- cph.handleApprovedBlockRequest(peer, abr)
            } yield res

          case bm: BlockMessage =>
            for {
              cph <- cphI.get
              res <- cph.handleBlockMessage(peer, bm)
            } yield res

          case ba: BlockApproval =>
            for {
              cph <- cphI.get
              res <- cph.handleBlockApproval(ba)
            } yield res

          case ub: UnapprovedBlock =>
            for {
              cph <- cphI.get
              res <- cph.handleUnapprovedBlock(peer, ub)
            } yield res

          case nab: NoApprovedBlockAvailable =>
            for {
              cph <- cphI.get
              res <- cph.handleNoApprovedBlockAvailable(nab)
            } yield res

        }
  }

  private def handleDoppelganger[F[_]: Monad: Log](
      peer: Node,
      b: BlockMessage,
      self: Validator
  ): F[Unit] =
    if (b.sender == self) {
      Log[F].warn(
        s"There is another node $peer proposing using the same private key as you. Or did you restart your node?"
      )
    } else {
      ().pure[F]
    }

  private def packetToBlockMessage(msg: Packet): Option[BlockMessage] =
    if (msg.typeId == transport.BlockMessage.id)
      Try(BlockMessage.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToApprovedBlock(msg: Packet): Option[ApprovedBlock] =
    if (msg.typeId == transport.ApprovedBlock.id)
      Try(ApprovedBlock.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToApprovedBlockRequest(msg: Packet): Option[ApprovedBlockRequest] =
    if (msg.typeId == transport.ApprovedBlockRequest.id)
      Try(ApprovedBlockRequest.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToBlockRequest(msg: Packet): Option[BlockRequest] =
    if (msg.typeId == transport.BlockRequest.id)
      Try(BlockRequest.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToForkChoiceTipRequest(msg: Packet): Option[ForkChoiceTipRequest] =
    if (msg.typeId == transport.ForkChoiceTipRequest.id)
      Try(ForkChoiceTipRequest.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToBlockApproval(msg: Packet): Option[BlockApproval] =
    if (msg.typeId == transport.BlockApproval.id)
      Try(BlockApproval.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToUnapprovedBlock(msg: Packet): Option[UnapprovedBlock] =
    if (msg.typeId == transport.UnapprovedBlock.id)
      Try(UnapprovedBlock.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def packetToNoApprovedBlockAvailable(msg: Packet): Option[NoApprovedBlockAvailable] =
    if (msg.typeId == transport.NoApprovedBlockAvailable.id)
      Try(NoApprovedBlockAvailable.parseFrom(msg.content.toByteArray)).toOption
    else None

  private def onApprovedBlockTransition[F[_]: Concurrent: Time: ErrorHandler: SafetyOracle: RPConfAsk: TransportLayer: ConnectionsCell: Log: BlockStore: LastApprovedBlock: BlockDagStorage: ExecutionEngineService](
      b: ApprovedBlock,
      validators: Set[ByteString],
      validatorId: Option[ValidatorIdentity],
      shardId: String
  ): F[Option[MultiParentCasper[F]]] =
    for {
      isValid <- Validate.approvedBlock[F](b, validators)
      casper <- if (isValid) {
                 for {
                   _            <- Log[F].info("Valid ApprovedBlock received!")
                   blockMessage = b.candidate.flatMap(_.block).get
                   dag          <- BlockDagStorage[F].getRepresentation
                   effects <- ExecEngineUtil.effectsForBlock[F](
                               blockMessage,
                               dag
                             )
                   (_, transforms) = effects
                   _ <- BlockStore[F].put(
                         blockMessage.blockHash,
                         blockMessage,
                         transforms
                       )
                   _ <- LastApprovedBlock[F].set(ApprovedBlockWithTransforms(b, transforms))
                   casper <- MultiParentCasper
                              .hashSetCasper[F](
                                validatorId,
                                blockMessage,
                                shardId
                              )
                 } yield Option(casper)
               } else
                 Log[F]
                   .info("Invalid ApprovedBlock received; refusing to add.")
                   .map(_ => none[MultiParentCasper[F]])
    } yield casper

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: CasperPacketHandler[F]
  ): CasperPacketHandler[T[F, ?]] =
    new CasperPacketHandler[T[F, ?]] {
      override def handle(peer: Node): PartialFunction[Packet, T[F, Unit]] =
        PartialFunction { (p: Packet) =>
          C.handle(peer)(p).liftM[T]
        }
    }

  private def sendNoApprovedBlockAvailable[F[_]: RPConfAsk: TransportLayer: Monad](
      peer: Node,
      identifier: String
  ): F[Unit] =
    for {
      local <- RPConfAsk[F].reader(_.local)
      //TODO remove NoApprovedBlockAvailable.nodeIdentifier, use `sender` provided by TransportLayer
      msg = Blob(local, noApprovedBlockAvailable(local, identifier))
      _   <- TransportLayer[F].stream(Seq(peer), msg)
    } yield ()

  private def noApprovedBlockAvailable(peer: Node, identifier: String): Packet = Packet(
    transport.NoApprovedBlockAvailable.id,
    NoApprovedBlockAvailable(identifier, peer.show).toByteString
  )

}

trait CasperPacketHandler[F[_]] {
  def handle(peer: Node): PartialFunction[Packet, F[Unit]]
}

abstract class CasperPacketHandlerInstances {
  implicit def eitherTCasperPacketHandler[E, F[_]: Monad: CasperPacketHandler[?[_]]]
    : CasperPacketHandler[EitherT[F, E, ?]] = CasperPacketHandler.forTrans[F, EitherT[?[_], E, ?]]
}
