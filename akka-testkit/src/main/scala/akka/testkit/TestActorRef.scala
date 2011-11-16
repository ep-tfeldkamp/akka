/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.actor._
import akka.util.ReflectiveAccess
import com.eaio.uuid.UUID
import akka.actor.Props._
import akka.actor.ActorSystem
import java.util.concurrent.atomic.AtomicLong
import akka.dispatch.Mailbox
import akka.event.EventStream

/**
 * This special ActorRef is exclusively for use during unit testing in a single-threaded environment. Therefore, it
 * overrides the dispatcher to CallingThreadDispatcher and sets the receiveTimeout to None. Otherwise,
 * it acts just like a normal ActorRef. You may retrieve a reference to the underlying actor to test internal logic.
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class TestActorRef[T <: Actor](
  _app: ActorSystemImpl,
  _deadLetterMailbox: Mailbox,
  _eventStream: EventStream,
  _scheduler: Scheduler,
  _props: Props,
  _supervisor: ActorRef,
  name: String)
  extends LocalActorRef(_app, _props.withDispatcher(new CallingThreadDispatcher(_deadLetterMailbox, _eventStream, _scheduler)), _supervisor, _supervisor.path / name, false) {
  /**
   * Directly inject messages into actor receive behavior. Any exceptions
   * thrown will be available to you, while still being able to use
   * become/unbecome and their message counterparts.
   */
  def apply(o: Any) { underlyingActorInstance.apply(o) }

  /**
   * Retrieve reference to the underlying actor, where the static type matches the factory used inside the
   * constructor. Beware that this reference is discarded by the ActorRef upon restarting the actor (should this
   * reference be linked to a supervisor). The old Actor may of course still be used in post-mortem assertions.
   */
  def underlyingActor: T = underlyingActorInstance.asInstanceOf[T]

  override def toString = "TestActor[" + address + "]"

  override def equals(other: Any) = other.isInstanceOf[TestActorRef[_]] && other.asInstanceOf[TestActorRef[_]].address == address
}

object TestActorRef {

  private val number = new AtomicLong
  private[testkit] def randomName: String = {
    val l = number.getAndIncrement()
    "$" + akka.util.Helpers.base64(l)
  }

  def apply[T <: Actor](factory: ⇒ T)(implicit app: ActorSystem): TestActorRef[T] = apply[T](Props(factory), randomName)

  def apply[T <: Actor](factory: ⇒ T, name: String)(implicit app: ActorSystem): TestActorRef[T] = apply[T](Props(factory), name)

  def apply[T <: Actor](props: Props)(implicit app: ActorSystem): TestActorRef[T] = apply[T](props, randomName)

  def apply[T <: Actor](props: Props, name: String)(implicit app: ActorSystem): TestActorRef[T] =
    apply[T](props, app.asInstanceOf[ActorSystemImpl].guardian, name)

  def apply[T <: Actor](props: Props, supervisor: ActorRef, name: String)(implicit app: ActorSystem): TestActorRef[T] = {
    val impl = app.asInstanceOf[ActorSystemImpl]
    new TestActorRef(impl, impl.deadLetterMailbox, app.eventStream, app.scheduler, props, supervisor, name)
  }

  def apply[T <: Actor](implicit m: Manifest[T], app: ActorSystem): TestActorRef[T] = apply[T](randomName)

  def apply[T <: Actor](name: String)(implicit m: Manifest[T], app: ActorSystem): TestActorRef[T] = apply[T](Props({
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    createInstance[T](m.erasure, noParams, noArgs) match {
      case Right(value) ⇒ value
      case Left(exception) ⇒ throw new ActorInitializationException(null,
        "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.", exception)
    }
  }), name)
}
