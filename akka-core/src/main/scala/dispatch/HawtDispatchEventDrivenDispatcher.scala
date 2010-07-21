/**
 * Copyright (C) 2010, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.ActorRef
import org.fusesource.hawtdispatch.DispatchQueue
import org.fusesource.hawtdispatch.ScalaDispatch._
import actors.threadpool.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

object HawtDispatchEventDrivenDispatcher {

  private val retained = new AtomicInteger()
  @volatile private var shutdownLatch: CountDownLatch = _

  private def retain = {
    if( retained.getAndIncrement == 0 ) {
      shutdownLatch = new CountDownLatch(1)
      new Thread("HawtDispatch Non-Daemon") {
        override def run = {
          try {
            shutdownLatch.await
          } catch {
            case _ =>
          }
          println("done");
        }
      }.start()
    }
  }

  private def release = {
    if( retained.decrementAndGet == 0 ) {
      shutdownLatch.countDown
      shutdownLatch = null
    }
  }

}

/**
 * <p>
 * An HawtDispatch based MessageDispatcher.  Actors with this dispatcher are executed
 * on the HawtDispatch thread pool which is restricted to only executing non blocking
 * operations.  Therefore, you can only use this dispatcher with actors which are purely
 * computational or which use non-blocking IO.
 * </p>
 * <p>
 * This dispatcher delivers messages to the actors in the order that they
 * were producer at the sender.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HawtDispatchEventDrivenDispatcher(val aggregate:Boolean=true, val parent:DispatchQueue=globalQueue) extends MessageDispatcher  {

  private val active = new AtomicBoolean(false)
  
  def start = {
    if( active.compareAndSet(false, true) ) {
      HawtDispatchEventDrivenDispatcher.retain
    }
  }

  def shutdown = {
    if( active.compareAndSet(true, false) ) {
      HawtDispatchEventDrivenDispatcher.release
    }
  }

  def isShutdown = !active.get

  def dispatch(invocation: MessageInvocation) = if(active.get()) {
    getMailbox(invocation.receiver).dispatch(invocation)
  } else {
    log.warning("%s is shut down,\n\tignoring the the messages sent to\n\t%s", toString, invocation.receiver)
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = receiver.mailbox.asInstanceOf[HawtDispatchMailbox]

  // hawtdispatch does not have a way to get queue sizes, getting an accurate
  // size can cause extra contention.. is this really needed?
  // TODO: figure out if this can be optional in akka
  override def mailboxSize(actorRef: ActorRef) = 0

  override def register(actorRef: ActorRef) = {
    if( actorRef.mailbox == null ) {
      val queue = parent.createSerialQueue(actorRef.toString)
      if( aggregate ) {
        actorRef.mailbox = new AggregatingHawtDispatchMailbox(queue)
      } else {
        actorRef.mailbox = new HawtDispatchMailbox(queue)
      }
    }
    super.register(actorRef)
  }

  override def toString = "HawtDispatchEventDrivenDispatcher"

}

class HawtDispatchMailbox(val queue:DispatchQueue) {
  def dispatch(invocation: MessageInvocation):Unit = {
    queue {
      invocation.invoke
    }
  }
}

class AggregatingHawtDispatchMailbox(queue:DispatchQueue) extends HawtDispatchMailbox(queue) {
  private val source = createSource(new ListEventAggregator[MessageInvocation](), queue)
  source.setEventHandler (^{drain_source} )
  source.resume

  private def drain_source = {
    source.getData.foreach { invocation =>
      invocation.invoke
    }
  }

  override def dispatch(invocation: MessageInvocation):Unit = {
    if ( getCurrentQueue == null ) {
      // we are being call from a non hawtdispatch thread, can't aggregate
      // it's events
      super.dispatch(invocation)
    } else {
      // we are being call from a hawtdispatch thread, use the dispatch source
      // so that multiple invocations issues on this thread will aggregate and then once
      // the thread runs out of work, they get transferred as a batch to the other thread.
      source.merge(invocation)
    }
  }
}
