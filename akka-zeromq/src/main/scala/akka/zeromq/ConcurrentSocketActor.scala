/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{ Socket, Poller }
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Promise, Future }
import akka.event.Logging
import annotation.tailrec
import akka.util.Duration
import java.util.concurrent.TimeUnit

private[zeromq] object ConcurrentSocketActor {
  private trait PollMsg
  private case object Poll extends PollMsg
  private case class ContinuePoll(frames: Vector[Frame]) extends PollMsg

  private class NoSocketHandleException() extends Exception("Couldn't create a zeromq socket.")

  private val DefaultContext = Context()
}
private[zeromq] class ConcurrentSocketActor(params: Seq[SocketOption]) extends Actor {

  import ConcurrentSocketActor._
  private val noBytes = Array[Byte]()
  private val zmqContext = params collectFirst { case c: Context ⇒ c } getOrElse DefaultContext

  private val deserializer = deserializerFromParams
  private val socket: Socket = socketFromParams
  private val poller: Poller = zmqContext.poller
  private val log = Logging(context.system, this)

  def receive = {
    case Poll                 ⇒ doPoll()
    case ContinuePoll(frames) ⇒ doPoll(currentFrames = frames)
    case ZMQMessage(frames)   ⇒ sendMessage(frames)
    case r: Request           ⇒ handleRequest(r)
    case Terminated(_)        ⇒ context stop self
  }

  private def handleRequest(msg: Request): Unit = msg match {
    case Send(frames)         ⇒ sendMessage(frames)
    case opt: SocketOption    ⇒ handleSocketOption(opt)
    case q: SocketOptionQuery ⇒ handleSocketOptionQuery(q)
  }

  private def handleConnectOption(msg: SocketConnectOption): Unit = msg match {
    case Connect(endpoint) ⇒ socket.connect(endpoint); notifyListener(Connecting)
    case Bind(endpoint)    ⇒ socket.bind(endpoint)
  }

  private def handlePubSubOption(msg: PubSubOption): Unit = msg match {
    case Subscribe(topic)   ⇒ socket.subscribe(topic.toArray)
    case Unsubscribe(topic) ⇒ socket.unsubscribe(topic.toArray)
  }

  private def handleSocketOption(msg: SocketOption): Unit = msg match {
    case x: SocketMeta               ⇒ throw new IllegalStateException("SocketMeta " + x + " only allowed for setting up a socket")
    case c: SocketConnectOption      ⇒ handleConnectOption(c)
    case ps: PubSubOption            ⇒ handlePubSubOption(ps)
    case Linger(value)               ⇒ socket.setLinger(value)
    case ReconnectIVL(value)         ⇒ socket.setReconnectIVL(value)
    case Backlog(value)              ⇒ socket.setBacklog(value)
    case ReconnectIVLMax(value)      ⇒ socket.setReconnectIVLMax(value)
    case MaxMsgSize(value)           ⇒ socket.setMaxMsgSize(value)
    case SendHighWatermark(value)    ⇒ socket.setSndHWM(value)
    case ReceiveHighWatermark(value) ⇒ socket.setRcvHWM(value)
    case HighWatermark(value)        ⇒ socket.setHWM(value)
    case Swap(value)                 ⇒ socket.setSwap(value)
    case Affinity(value)             ⇒ socket.setAffinity(value)
    case Identity(value)             ⇒ socket.setIdentity(value)
    case Rate(value)                 ⇒ socket.setRate(value)
    case RecoveryInterval(value)     ⇒ socket.setRecoveryInterval(value)
    case MulticastLoop(value)        ⇒ socket.setMulticastLoop(value)
    case MulticastHops(value)        ⇒ socket.setMulticastHops(value)
    case SendBufferSize(value)       ⇒ socket.setSendBufferSize(value)
    case ReceiveBufferSize(value)    ⇒ socket.setReceiveBufferSize(value)
  }

  private def handleSocketOptionQuery(msg: SocketOptionQuery): Unit = msg match {
    case Linger               ⇒ sender ! socket.getLinger
    case ReconnectIVL         ⇒ sender ! socket.getReconnectIVL
    case Backlog              ⇒ sender ! socket.getBacklog
    case ReconnectIVLMax      ⇒ sender ! socket.getReconnectIVLMax
    case MaxMsgSize           ⇒ sender ! socket.getMaxMsgSize
    case SendHighWatermark    ⇒ sender ! socket.getSndHWM
    case ReceiveHighWatermark ⇒ sender ! socket.getRcvHWM
    case Swap                 ⇒ sender ! socket.getSwap
    case Affinity             ⇒ sender ! socket.getAffinity
    case Identity             ⇒ sender ! socket.getIdentity
    case Rate                 ⇒ sender ! socket.getRate
    case RecoveryInterval     ⇒ sender ! socket.getRecoveryInterval
    case MulticastLoop        ⇒ sender ! socket.hasMulticastLoop
    case MulticastHops        ⇒ sender ! socket.getMulticastHops
    case SendBufferSize       ⇒ sender ! socket.getSendBufferSize
    case ReceiveBufferSize    ⇒ sender ! socket.getReceiveBufferSize
    case FileDescriptor       ⇒ sender ! socket.getFD
  }

  override def preStart {
    watchListener()
    setupSocket()
    poller.register(socket, Poller.POLLIN)
    setupConnection()
    self ! Poll
  }

  private def setupConnection() {
    params filter (_.isInstanceOf[SocketConnectOption]) foreach { self ! _ }
    params filter (_.isInstanceOf[PubSubOption]) foreach { self ! _ }
  }

  private def socketFromParams() = {
    require(ZeroMQExtension.check[SocketType.ZMQSocketType](params), "A socket type is required")
    (params
      collectFirst { case t: SocketType.ZMQSocketType ⇒ zmqContext.socket(t) }
      getOrElse (throw new NoSocketHandleException))
  }

  private def deserializerFromParams = {
    params collectFirst { case d: Deserializer ⇒ d } getOrElse new ZMQMessageDeserializer
  }

  private def setupSocket() = {
    params foreach {
      case _: SocketConnectOption | _: PubSubOption | _: SocketMeta ⇒ // ignore, handled differently
      case m ⇒ self ! m
    }
  }

  override def postStop {
    try {
      poller.unregister(socket)
      if (socket != null) socket.close
    } finally {
      notifyListener(Closed)
    }
  }

  private def sendMessage(frames: Seq[Frame]) {
    def sendBytes(bytes: Seq[Byte], flags: Int) = socket.send(bytes.toArray, flags)
    val iter = frames.iterator
    while (iter.hasNext) {
      val payload = iter.next.payload
      val flags = if (iter.hasNext) JZMQ.SNDMORE else 0
      sendBytes(payload, flags)
    }
  }

  // this is a “PollMsg=>Unit” which either polls or schedules Poll, depending on the sign of the timeout
  private val doPollTimeout = {
    val fromConfig = params collectFirst { case PollTimeoutDuration(duration) ⇒ duration }
    val duration = fromConfig getOrElse ZeroMQExtension(context.system).DefaultPollTimeout
    if (duration > Duration.Zero) { (msg: PollMsg) ⇒
      // for positive timeout values, do poll (i.e. block this thread)
      poller.poll(duration.toMicros)
      self ! msg
    } else {
      val d = -duration

      { (msg: PollMsg) ⇒
        // for negative timeout values, schedule Poll token -duration into the future
        context.system.scheduler.scheduleOnce(d, self, msg)
        ()
      }
    }
  }

  @tailrec private def doPoll(togo: Int = 10, currentFrames: Vector[Frame] = Vector.empty): Unit =
    receiveMessage(currentFrames) match {
      case null  ⇒ // receiveMessage has already done something special here
      case Seq() ⇒ doPollTimeout(Poll)
      case frames ⇒
        notifyListener(deserializer(frames))
        if (togo > 0) doPoll(togo - 1)
        else self ! Poll
    }

  @tailrec private def receiveMessage(currentFrames: Vector[Frame]): Seq[Frame] = {
    socket.recv(JZMQ.NOBLOCK) match {
      case null ⇒
        if (currentFrames.isEmpty) currentFrames
        else {
          doPollTimeout(ContinuePoll(currentFrames))
          null
        }
      case bytes ⇒
        val frames = currentFrames :+ Frame(if (bytes.length == 0) noBytes else bytes)
        if (socket.hasReceiveMore) receiveMessage(frames) else frames
    }
  }

  private val listenerOpt = params collectFirst { case Listener(l) ⇒ l }
  private def watchListener() {
    listenerOpt foreach context.watch
  }

  private def notifyListener(message: Any) {
    listenerOpt foreach { _ ! message }
  }
}
