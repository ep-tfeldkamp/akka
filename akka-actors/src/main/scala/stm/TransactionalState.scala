/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import stm.TransactionManagement
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

/**
 * Scala API.
 * <p/>
 * Example Scala usage:
 * <pre>
 * val myMap = TransactionalState.newMap
 * </pre>
 */
object TransactionalState extends TransactionalState

/**
 * Java API.
 * <p/>
 * Example Java usage:
 * <pre>
 * TransactionalState state = new TransactionalState();
 * TransactionalMap myMap = state.newMap();
 * </pre>
 */
class TransactionalState {
  def newMap[K, V]: TransactionalMap[K, V] = new InMemoryTransactionalMap[K, V]
  def newVector[T]: TransactionalVector[T] = new InMemoryTransactionalVector[T]
  def newRef[T]: TransactionalRef[T] = new TransactionalRef[T]
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  // FIXME: won't work across the cluster
  var uuid = Uuid.newUuid.toString

  private[akka] def begin
  private[akka] def commit
  private[akka] def rollback

  protected def verifyTransaction = {
    val cflowTx = TransactionManagement.threadBoundTx.get
    if (!cflowTx.isDefined) {
      throw new IllegalStateException("Can't access transactional reference outside the scope of a transaction [" + this + "]")
    } else {
      cflowTx.get.register(this)
    }
  }
}

/**
 * Base trait for all state implementations (persistent or in-memory).
 *
 * FIXME: Create Java versions using pcollections
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionalMap[K, V] extends Transactional with scala.collection.mutable.Map[K, V] {
  override def hashCode: Int = System.identityHashCode(this);
  override def equals(other: Any): Boolean = false
  def remove(key: K)
}

/**
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalMap[K, V] extends TransactionalMap[K, V] {
  protected[akka] var state = new HashTrie[K, V]
  protected[akka] var snapshot = state

  // ---- For Transactional ----
  override def begin = snapshot = state
  override def commit = snapshot = state
  override def rollback = state = snapshot

  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def contains(key: K): Boolean = {
    verifyTransaction
    state.contains(key)
  }

  override def clear: Unit = {
    verifyTransaction
    state = new HashTrie[K, V]
  }

  override def size: Int = {
    verifyTransaction
    state.size
  }

  // ---- For scala.collection.mutable.Map ----
  override def remove(key: K): Unit = {
    verifyTransaction
    state = state - key
  }

  override def elements: Iterator[(K, V)] = {
//    verifyTransaction
    state.elements
  }

  override def get(key: K): Option[V] = {
    verifyTransaction
    state.get(key)
  }

  override def put(key: K, value: V): Option[V] = {
    verifyTransaction
    val oldValue = state.get(key)
    state = state.update(key, value)
    oldValue
  }

  override def -=(key: K): Unit = {
    verifyTransaction
    remove(key)
  }

  override def update(key: K, value: V): Unit = {
    verifyTransaction
    put(key, value)
  }
}

/**
 * Base for all transactional vector implementations.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
  override def hashCode: Int = System.identityHashCode(this);
  override def equals(other: Any): Boolean = false

  def add(elem: T)

  def get(index: Int): T

  def getRange(start: Int, count: Int): List[T]
}

/**
 * Implements an in-memory transactional vector.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalVector[T] extends TransactionalVector[T] {
  private[akka] var state: Vector[T] = EmptyVector
  private[akka] var snapshot = state

  def add(elem: T) = {
    verifyTransaction
    state = state + elem
  }

  def get(index: Int): T = {
    verifyTransaction
    state(index)
  }

  def getRange(start: Int, count: Int): List[T] = {
    verifyTransaction
    state.slice(start, count).toList.asInstanceOf[List[T]]
  }

  // ---- For Transactional ----
  override def begin = snapshot = state

  override def commit = snapshot = state

  override def rollback = state = snapshot

  // ---- For Seq ----
  def length: Int = {
    verifyTransaction
    state.length
  }

  def apply(index: Int): T = {
    verifyTransaction
    state(index)
  }

  override def elements: Iterator[T] = {
    //verifyTransaction
    state.elements
  }

  override def toList: List[T] = {
    verifyTransaction
    state.toList
  }
}

/**
 * Implements a transactional reference.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalRef[T] extends Transactional {
  private[akka] var ref: Option[T] = None
  private[akka] var snapshot: Option[T] = None

  override def begin = if (ref.isDefined) snapshot = Some(ref.get)

  override def commit = if (ref.isDefined) snapshot = Some(ref.get)

  override def rollback = if (snapshot.isDefined) ref = Some(snapshot.get)

  def swap(elem: T) = {
    verifyTransaction
    ref = Some(elem)
  }

  def get: Option[T] = {
    verifyTransaction
    ref
  }

  def getOrElse(default: => T): T = {
    verifyTransaction
    ref.getOrElse(default)
  }

  def isDefined: Boolean = {
    verifyTransaction
    ref.isDefined
  }
}
