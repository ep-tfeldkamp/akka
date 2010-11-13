/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.transactor.typed

import akka.transactor.{Coordinated => CoordinatedObject}
import akka.stm.Atomic

import scala.util.DynamicVariable

/**
 * Coordinating transactions between typed actors.
 *
 * Example ''(in Scala)''
 *
 * {{{
 * trait Counter {
 *   @Coordinated def increment: Unit
 *   def get: Int
 * }
 *
 * class CounterImpl extends TypedActor with Counter {
 *   val ref = Ref(0)
 *   def increment = ref alter (_ + 1)
 *   def get = ref.get
 * }
 *
 *
 * val counter1 = TypedActor.newInstance(classOf[Counter], classOf[CounterImpl])
 * val counter2 = TypedActor.newInstance(classOf[Counter], classOf[CounterImpl])
 *
 * coordinate {
 *   counter1.increment
 *   counter2.increment
 * }
 * }}}
 */
object Coordination {
  private[akka] val coordinated = new DynamicVariable[CoordinatedObject](null)
  private[akka] val firstParty = new DynamicVariable[Boolean](false)

  /**
   * For creating a coordination between typed actors that use
   * the [[akka.transactor.typed.Coordinated]] annotation.
   * Coordinated transactions will wait for all other transactions in the coordination
   * before committing. The timeout is specified by the default transaction factory.
   * It's possible to specify whether or not this `coordinate` block waits for all of
   * the transactions to complete - the default is that it does.
   */
  def coordinate[U](wait: Boolean = true)(body: => U): Unit = {
    firstParty.value = !wait
    coordinated.withValue(CoordinatedObject()) {
      body
      if (wait) coordinated.value.await
    }
    firstParty.value = false
  }

  /**
   * For creating a coordination between typed actors that use
   * the [[akka.transactor.typed.Coordinated]] annotation.
   * Coordinated transactions will wait for all other transactions in the coordination
   * before committing. The timeout is specified by the default transaction factory.
   */
  def coordinate[U](body: => U): Unit = coordinate(true)(body)

  /**
   * Java API: coordinate that accepts an [[akka.stm.Atomic]].
   * For creating a coordination between typed actors that use
   * the [[akka.transactor.typed.Coordinated]] annotation.
   * Coordinated transactions will wait for all other transactions in the coordination
   * before committing. The timeout is specified by the default transaction factory.
   * Use the `wait` parameter to specify whether or not this `coordinate` block
   * waits for all of the transactions to complete.
   */
  def coordinate[U](wait: Boolean, jatomic: Atomic[U]): Unit = coordinate(wait)(jatomic.atomically)
}
