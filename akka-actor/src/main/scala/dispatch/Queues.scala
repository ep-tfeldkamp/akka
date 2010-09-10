/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import concurrent.forkjoin.LinkedTransferQueue
import java.util.concurrent.{TimeUnit, Semaphore}
import java.util.Iterator
import se.scalablesolutions.akka.util.Logger

class BoundableTransferQueue[E <: AnyRef](val capacity: Int) extends LinkedTransferQueue[E] {
  val bounded = (capacity > 0)

  protected lazy val guard = new Semaphore(capacity)

  override def take(): E = {
    if (!bounded) {
      super.take
    } else {
      val e = super.take
      if (e ne null) guard.release
      e
    }
  }

  override def poll(): E = {
    if (!bounded) {
      super.poll
    } else {
      val e = super.poll
      if (e ne null) guard.release
      e
    }
  }

  override def poll(timeout: Long, unit: TimeUnit): E = {
    if (!bounded) {
      super.poll(timeout,unit)
    } else {
      val e = super.poll(timeout,unit)
      if (e ne null) guard.release
      e
    }
  }

  override def remainingCapacity: Int = {
    if (!bounded) super.remainingCapacity
    else guard.availablePermits
  }

  override def remove(o: AnyRef): Boolean = {
    if (!bounded) {
      super.remove(o)
    } else {
      if (super.remove(o)) {
        guard.release
        true
      } else false
    }
  }

  override def offer(e: E): Boolean = {
    if (!bounded) {
      super.offer(e)
    } else {
      if (guard.tryAcquire) {
        val result = try {
          super.offer(e)
        } catch {
          case e => guard.release; throw e
        }
        if (!result) guard.release
        result
      } else false
    }
  }

  override def offer(e: E, timeout: Long, unit: TimeUnit): Boolean = {
    if (!bounded) {
      super.offer(e,timeout,unit)
    } else {
      if (guard.tryAcquire(timeout,unit)) {
        val result = try {
          super.offer(e)
        } catch {
          case e => guard.release; throw e
        }
        if (!result) guard.release
        result
      } else false
    }
  }

  override def add(e: E): Boolean = {
    if (!bounded) {
      super.add(e)
    } else {
      if (guard.tryAcquire) {
        val result = try {
          super.add(e)
        } catch {
          case e => guard.release; throw e
        }
        if (!result) guard.release
        result
      } else false
    }
  }

  override def put(e :E): Unit = {
    if (!bounded) {
      super.put(e)
    } else {
      guard.acquire
      try {
        super.put(e)
      } catch {
        case e => guard.release; throw e
      }
    }
  }

  override def tryTransfer(e: E): Boolean = {
    if (!bounded) {
      super.tryTransfer(e)
    } else {
      if (guard.tryAcquire) {
        val result = try {
          super.tryTransfer(e)
        } catch {
          case e => guard.release; throw e
        }
        if (!result) guard.release
        result
      } else false
    }
  }

  override def tryTransfer(e: E, timeout: Long, unit: TimeUnit): Boolean = {
    if (!bounded) {
      super.tryTransfer(e,timeout,unit)
    } else {
      if (guard.tryAcquire(timeout,unit)) {
        val result = try {
          super.tryTransfer(e)
        } catch {
          case e => guard.release; throw e
        }
        if (!result) guard.release
        result
      } else false
    }
  }
  
  override def transfer(e: E): Unit = {
    if (!bounded) {
      super.transfer(e)
    } else {
      if (guard.tryAcquire) {
        try {
          super.transfer(e)
        } catch {
          case e => guard.release; throw e
        }
      }
    }
  }

  override def iterator: Iterator[E] = {
    val it = super.iterator
    new Iterator[E] {
      def hasNext = it.hasNext
      def next = it.next
      def remove {
        it.remove
        if (bounded)
          guard.release //Assume remove worked if no exception was thrown
      }
    }
  }
}