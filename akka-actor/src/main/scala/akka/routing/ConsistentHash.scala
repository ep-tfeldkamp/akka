/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import scala.collection.immutable.TreeMap

/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2011, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

/**
 * An implementation of Austin Appleby's MurmurHash 3.0 algorithm
 *  (32 bit version); reference: http://code.google.com/p/smhasher
 *
 *  This is the hash used by collections and case classes (including
 *  tuples).
 *
 *  @author  Rex Kerr
 *  @version 2.9
 *  @since   2.9
 */

import java.lang.Integer.{ rotateLeft ⇒ rotl }

// =============================================================================================
// Adapted from HashRing.scala in Debasish Ghosh's Redis Client, licensed under Apache 2 license
// =============================================================================================

/**
 * Consistent Hashing node ring abstraction.
 *
 * A good explanation of Consistent Hashing:
 * http://weblogs.java.net/blog/tomwhite/archive/2007/11/consistent_hash.html
 *
 * Note that toString of the ring nodes are used for the node
 * hash, i.e. make sure it is different for different nodes.
 *
 * Not thread-safe, to be used from within an Actor or protected some other way.
 */
class ConsistentHash[T](nodes: Iterable[T], replicas: Int) {
  private var ring = TreeMap[Int, T]()

  if (replicas < 1) throw new IllegalArgumentException("replicas must be >= 1")

  nodes.foreach(this += _)

  /**
   * Adds a node to the ring.
   */
  def +=(node: T): Unit = {
    (1 to replicas) foreach { replica ⇒
      ring += (nodeHashFor(node, replica) -> node)
    }
  }

  /**
   * Adds a node to the ring.
   * JAVA API
   */
  def add(node: T): Unit = this += node

  /**
   * Removes a node from the ring.
   */
  def -=(node: T): Unit = {
    (1 to replicas) foreach { replica ⇒
      ring -= nodeHashFor(node, replica)
    }
  }

  /**
   * Removes a node from the ring.
   * JAVA API
   */
  def remove(node: T): Unit = this -= node

  /**
   * Get the node responsible for the data key.
   * Can only be used if nodes exists in the ring,
   * otherwise throws `IllegalStateException`
   */
  def nodeFor(key: Array[Byte]): T = {
    if (isEmpty) throw new IllegalStateException("Can't get node for [%s] from an empty ring" format key)
    val hash = hashFor(key)
    def nextClockwise: T = {
      val (ringKey, node) = ring.rangeImpl(Some(hash), None).headOption.getOrElse(ring.head)
      node
    }
    ring.getOrElse(hash, nextClockwise)
  }

  /**
   * Is the ring empty, i.e. no nodes added or all removed.
   */
  def isEmpty: Boolean = ring.isEmpty

  private def nodeHashFor(node: T, replica: Int): Int = {
    hashFor((node + ":" + replica).getBytes("UTF-8"))
  }

  private def hashFor(bytes: Array[Byte]): Int = {
    val hash = MurmurHash.arrayHash(bytes)
    if (hash == Int.MinValue) hash + 1
    math.abs(hash)
  }
}

/**
 * A class designed to generate well-distributed non-cryptographic
 *  hashes.  It is designed to be passed to a collection's foreach method,
 *  or can take individual hash values with append.  Its own hash code is
 *  set equal to the hash code of whatever it is hashing.
 */
class MurmurHash[@specialized(Int, Long, Float, Double) T](seed: Int) extends (T ⇒ Unit) {
  import MurmurHash._

  private var h = startHash(seed)
  private var c = hiddenMagicA
  private var k = hiddenMagicB
  private var hashed = false
  private var hashvalue = h

  /** Begin a new hash using the same seed. */
  def reset(): Unit = {
    h = startHash(seed)
    c = hiddenMagicA
    k = hiddenMagicB
    hashed = false
  }

  /** Incorporate the hash value of one item. */
  def apply(t: T): Unit = {
    h = extendHash(h, t.##, c, k)
    c = nextMagicA(c)
    k = nextMagicB(k)
    hashed = false
  }

  /** Incorporate a known hash value. */
  def append(i: Int): Unit = {
    h = extendHash(h, i, c, k)
    c = nextMagicA(c)
    k = nextMagicB(k)
    hashed = false
  }

  /** Retrieve the hash value */
  def hash: Int = {
    if (!hashed) {
      hashvalue = finalizeHash(h)
      hashed = true
    }
    hashvalue
  }

  override def hashCode: Int = hash
}

/**
 * An object designed to generate well-distributed non-cryptographic
 *  hashes.  It is designed to hash a collection of integers; along with
 *  the integers to hash, it generates two magic streams of integers to
 *  increase the distribution of repetitive input sequences.  Thus,
 *  three methods need to be called at each step (to start and to
 *  incorporate a new integer) to update the values.  Only one method
 *  needs to be called to finalize the hash.
 */

object MurmurHash {
  // Magic values used for MurmurHash's 32 bit hash.
  // Don't change these without consulting a hashing expert!
  final private val visibleMagic: Int = 0x971e137b
  final private val hiddenMagicA: Int = 0x95543787
  final private val hiddenMagicB: Int = 0x2ad7eb25
  final private val visibleMixer: Int = 0x52dce729
  final private val hiddenMixerA: Int = 0x7b7d159c
  final private val hiddenMixerB: Int = 0x6bce6396
  final private val finalMixer1: Int = 0x85ebca6b
  final private val finalMixer2: Int = 0xc2b2ae35

  // Arbitrary values used for hashing certain classes
  final private val seedString: Int = 0xf7ca7fd2
  final private val seedArray: Int = 0x3c074a61

  /** The first 23 magic integers from the first stream are stored here */
  val storedMagicA: Array[Int] =
    Iterator.iterate(hiddenMagicA)(nextMagicA).take(23).toArray

  /** The first 23 magic integers from the second stream are stored here */
  val storedMagicB: Array[Int] =
    Iterator.iterate(hiddenMagicB)(nextMagicB).take(23).toArray

  /** Begin a new hash with a seed value. */
  def startHash(seed: Int): Int = seed ^ visibleMagic

  /** The initial magic integers in the first stream. */
  def startMagicA: Int = hiddenMagicA

  /** The initial magic integer in the second stream. */
  def startMagicB: Int = hiddenMagicB

  /**
   * Incorporates a new value into an existing hash.
   *
   *  @param   hash    the prior hash value
   *  @param  value    the new value to incorporate
   *  @param magicA    a magic integer from the stream
   *  @param magicB    a magic integer from a different stream
   *  @return          the updated hash value
   */
  def extendHash(hash: Int, value: Int, magicA: Int, magicB: Int): Int =
    (hash ^ rotl(value * magicA, 11) * magicB) * 3 + visibleMixer

  /** Given a magic integer from the first stream, compute the next */
  def nextMagicA(magicA: Int): Int = magicA * 5 + hiddenMixerA

  /** Given a magic integer from the second stream, compute the next */
  def nextMagicB(magicB: Int): Int = magicB * 5 + hiddenMixerB

  /** Once all hashes have been incorporated, this performs a final mixing */
  def finalizeHash(hash: Int): Int = {
    var i = (hash ^ (hash >>> 16))
    i *= finalMixer1
    i ^= (i >>> 13)
    i *= finalMixer2
    i ^= (i >>> 16)
    i
  }

  /** Compute a high-quality hash of an array */
  def arrayHash[@specialized T](a: Array[T]): Int = {
    var h = startHash(a.length * seedArray)
    var c = hiddenMagicA
    var k = hiddenMagicB
    var j = 0
    while (j < a.length) {
      h = extendHash(h, a(j).##, c, k)
      c = nextMagicA(c)
      k = nextMagicB(k)
      j += 1
    }
    finalizeHash(h)
  }

  /** Compute a high-quality hash of a string */
  def stringHash(s: String): Int = {
    var h = startHash(s.length * seedString)
    var c = hiddenMagicA
    var k = hiddenMagicB
    var j = 0
    while (j + 1 < s.length) {
      val i = (s.charAt(j) << 16) + s.charAt(j + 1);
      h = extendHash(h, i, c, k)
      c = nextMagicA(c)
      k = nextMagicB(k)
      j += 2
    }
    if (j < s.length) h = extendHash(h, s.charAt(j), c, k)
    finalizeHash(h)
  }

  /**
   * Compute a hash that is symmetric in its arguments--that is,
   *  where the order of appearance of elements does not matter.
   *  This is useful for hashing sets, for example.
   */
  def symmetricHash[T](xs: TraversableOnce[T], seed: Int): Int = {
    var a, b, n = 0
    var c = 1
    xs.foreach(i ⇒ {
      val h = i.##
      a += h
      b ^= h
      if (h != 0) c *= h
      n += 1
    })
    var h = startHash(seed * n)
    h = extendHash(h, a, storedMagicA(0), storedMagicB(0))
    h = extendHash(h, b, storedMagicA(1), storedMagicB(1))
    h = extendHash(h, c, storedMagicA(2), storedMagicB(2))
    finalizeHash(h)
  }
}
