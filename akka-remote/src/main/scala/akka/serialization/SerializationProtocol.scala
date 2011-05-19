/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.serialization

import akka.dispatch.MessageInvocation
import akka.remote.protocol.RemoteProtocol._
import akka.remote.protocol.RemoteProtocol

import akka.config.Supervision._
import akka.actor.{ uuidFrom, newUuid }
import akka.actor._

import scala.collection.immutable.Stack

import com.google.protobuf.ByteString
import akka.util.ReflectiveAccess
import java.net.InetSocketAddress
import akka.remote.{ RemoteClientSettings, MessageSerializer }

/**
 * Module for local actor serialization.
 */
object ActorSerialization {
  def fromBinary[T <: Actor](bytes: Array[Byte], homeAddress: InetSocketAddress)(implicit format: Format[T]): ActorRef =
    fromBinaryToLocalActorRef(bytes, Some(homeAddress), format)

  def fromBinary[T <: Actor](bytes: Array[Byte])(implicit format: Format[T]): ActorRef =
    fromBinaryToLocalActorRef(bytes, None, format)

  def toBinary[T <: Actor](a: ActorRef, serializeMailBox: Boolean = true)(implicit format: Format[T]): Array[Byte] =
    toSerializedActorRefProtocol(a, format, serializeMailBox).toByteArray

  // wrapper for implicits to be used by Java
  def fromBinaryJ[T <: Actor](bytes: Array[Byte], format: Format[T]): ActorRef =
    fromBinary(bytes)(format)

  // wrapper for implicits to be used by Java
  def toBinaryJ[T <: Actor](a: ActorRef, format: Format[T], srlMailBox: Boolean = true): Array[Byte] =
    toBinary(a, srlMailBox)(format)

  private[akka] def toSerializedActorRefProtocol[T <: Actor](
    actorRef: ActorRef, format: Format[T], serializeMailBox: Boolean = true): SerializedActorRefProtocol = {
    val lifeCycleProtocol: Option[LifeCycleProtocol] = {
      actorRef.lifeCycle match {
        case Permanent          ⇒ Some(LifeCycleProtocol.newBuilder.setLifeCycle(LifeCycleType.PERMANENT).build)
        case Temporary          ⇒ Some(LifeCycleProtocol.newBuilder.setLifeCycle(LifeCycleType.TEMPORARY).build)
        case UndefinedLifeCycle ⇒ None //No need to send the undefined lifecycle over the wire  //builder.setLifeCycle(LifeCycleType.UNDEFINED)
      }
    }

    val builder = SerializedActorRefProtocol.newBuilder
      .setUuid(UuidProtocol.newBuilder.setHigh(actorRef.uuid.getTime).setLow(actorRef.uuid.getClockSeqAndNode).build)
      .setAddress(actorRef.address)
      .setActorClassname(actorRef.actorInstance.get.getClass.getName)
      .setTimeout(actorRef.timeout)

    if (serializeMailBox == true) {
      val messages =
        actorRef.mailbox match {
          case q: java.util.Queue[MessageInvocation] ⇒
            val l = new scala.collection.mutable.ListBuffer[MessageInvocation]
            val it = q.iterator
            while (it.hasNext == true) l += it.next
            l
        }

      val requestProtocols =
        messages.map(m ⇒
          RemoteActorSerialization.createRemoteMessageProtocolBuilder(
            Some(actorRef),
            Left(actorRef.uuid),
            actorRef.address,
            actorRef.timeout,
            Right(m.message),
            false,
            actorRef.getSender,
            RemoteClientSettings.SECURE_COOKIE).build)

      requestProtocols.foreach(rp ⇒ builder.addMessages(rp))
    }

    actorRef.receiveTimeout.foreach(builder.setReceiveTimeout(_))
    builder.setActorInstance(ByteString.copyFrom(format.toBinary(actorRef.actor.asInstanceOf[T])))
    lifeCycleProtocol.foreach(builder.setLifeCycle(_))
    actorRef.supervisor.foreach(s ⇒ builder.setSupervisor(RemoteActorSerialization.toRemoteActorRefProtocol(s)))
    if (!actorRef.hotswap.isEmpty) builder.setHotswapStack(ByteString.copyFrom(Serializers.Java.toBinary(actorRef.hotswap)))
    builder.build
  }

  private def fromBinaryToLocalActorRef[T <: Actor](
    bytes: Array[Byte],
    homeAddress: Option[InetSocketAddress],
    format: Format[T]): ActorRef = {
    val builder = SerializedActorRefProtocol.newBuilder.mergeFrom(bytes)
    fromProtobufToLocalActorRef(builder.build, format, None)
  }

  private[akka] def fromProtobufToLocalActorRef[T <: Actor](
    protocol: SerializedActorRefProtocol, format: Format[T], loader: Option[ClassLoader]): ActorRef = {

    val serializer =
      if (format.isInstanceOf[SerializerBasedActorFormat[_]])
        Some(format.asInstanceOf[SerializerBasedActorFormat[_]].serializer)
      else None

    val lifeCycle =
      if (protocol.hasLifeCycle) {
        protocol.getLifeCycle.getLifeCycle match {
          case LifeCycleType.PERMANENT ⇒ Permanent
          case LifeCycleType.TEMPORARY ⇒ Temporary
          case unknown                 ⇒ throw new IllegalActorStateException("LifeCycle type is not valid [" + unknown + "]")
        }
      } else UndefinedLifeCycle

    val supervisor =
      if (protocol.hasSupervisor) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(protocol.getSupervisor, loader))
      else None

    val hotswap =
      if (serializer.isDefined && protocol.hasHotswapStack) serializer.get
        .fromBinary(protocol.getHotswapStack.toByteArray, Some(classOf[Stack[PartialFunction[Any, Unit]]]))
        .asInstanceOf[Stack[PartialFunction[Any, Unit]]]
      else Stack[PartialFunction[Any, Unit]]()

    val classLoader = loader.getOrElse(getClass.getClassLoader)

    val factory = () ⇒ {
      val actorClass = classLoader.loadClass(protocol.getActorClassname)
      if (format.isInstanceOf[SerializerBasedActorFormat[_]])
        format.asInstanceOf[SerializerBasedActorFormat[_]].serializer.fromBinary(
          protocol.getActorInstance.toByteArray, Some(actorClass)).asInstanceOf[Actor]
      else actorClass.newInstance.asInstanceOf[Actor]
    }

    val ar = new LocalActorRef(
      uuidFrom(protocol.getUuid.getHigh, protocol.getUuid.getLow),
      protocol.getAddress,
      if (protocol.hasTimeout) protocol.getTimeout else Actor.TIMEOUT,
      if (protocol.hasReceiveTimeout) Some(protocol.getReceiveTimeout) else None,
      lifeCycle,
      supervisor,
      hotswap,
      factory)

    val messages = protocol.getMessagesList.toArray.toList.asInstanceOf[List[RemoteMessageProtocol]]
    messages.foreach(message ⇒ ar ! MessageSerializer.deserialize(message.getMessage))

    if (format.isInstanceOf[SerializerBasedActorFormat[_]] == false)
      format.fromBinary(protocol.getActorInstance.toByteArray, ar.actor.asInstanceOf[T])
    ar
  }
}

object RemoteActorSerialization {
  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte]): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, None)

  /**
   * Deserializes a byte array (Array[Byte]) into an RemoteActorRef instance.
   */
  def fromBinaryToRemoteActorRef(bytes: Array[Byte], loader: ClassLoader): ActorRef =
    fromProtobufToRemoteActorRef(RemoteActorRefProtocol.newBuilder.mergeFrom(bytes).build, Some(loader))

  /**
   * Deserializes a RemoteActorRefProtocol Protocol Buffers (protobuf) Message into an RemoteActorRef instance.
   */
  private[akka] def fromProtobufToRemoteActorRef(protocol: RemoteActorRefProtocol, loader: Option[ClassLoader]): ActorRef = {
    val ref = RemoteActorRef(
      protocol.getAddress,
      protocol.getTimeout,
      loader)
    ref
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): RemoteActorRefProtocol = {
    actor match {
      case ar: LocalActorRef ⇒ Actor.remote.registerByUuid(ar)
      case _                 ⇒ {}
    }
    RemoteActorRefProtocol.newBuilder
      .setAddress("uuid:" + actor.uuid.toString)
      .setTimeout(actor.timeout)
      .build
  }

  def createRemoteMessageProtocolBuilder(
    actorRef: Option[ActorRef],
    replyUuid: Either[Uuid, UuidProtocol],
    actorAddress: String,
    timeout: Long,
    message: Either[Throwable, Any],
    isOneWay: Boolean,
    senderOption: Option[ActorRef],
    secureCookie: Option[String]): RemoteMessageProtocol.Builder = {

    val uuidProtocol = replyUuid match {
      case Left(uid)       ⇒ UuidProtocol.newBuilder.setHigh(uid.getTime).setLow(uid.getClockSeqAndNode).build
      case Right(protocol) ⇒ protocol
    }

    val actorInfoBuilder = ActorInfoProtocol.newBuilder
      .setUuid(uuidProtocol)
      .setAddress(actorAddress)
      .setTimeout(timeout)

    val actorInfo = actorInfoBuilder.build
    val messageBuilder = RemoteMessageProtocol.newBuilder
      .setUuid({
        val messageUuid = newUuid
        UuidProtocol.newBuilder.setHigh(messageUuid.getTime).setLow(messageUuid.getClockSeqAndNode).build
      })
      .setActorInfo(actorInfo)
      .setOneWay(isOneWay)

    message match {
      case Right(message) ⇒
        messageBuilder.setMessage(MessageSerializer.serialize(message))
      case Left(exception) ⇒
        messageBuilder.setException(ExceptionProtocol.newBuilder
          .setClassname(exception.getClass.getName)
          .setMessage(empty(exception.getMessage))
          .build)
    }

    def empty(s: String): String = s match {
      case null ⇒ ""
      case s    ⇒ s
    }

    secureCookie.foreach(messageBuilder.setCookie(_))

    /* TODO invent new supervision strategy
      actorRef.foreach { ref =>
      ref.registerSupervisorAsRemoteActor.foreach { id =>
        messageBuilder.setSupervisorUuid(
          UuidProtocol.newBuilder
              .setHigh(id.getTime)
              .setLow(id.getClockSeqAndNode)
              .build)
      }
    } */

    if (senderOption.isDefined)
      messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }
}
