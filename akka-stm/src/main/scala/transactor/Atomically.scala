/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.transactor

import akka.stm.TransactionFactory

/**
 * For Java-friendly coordinated atomic blocks.
 *
 * Similar to [[akka.stm.Atomic]] but used to pass a block to Coordinated.atomic
 * or to Coordination.coordinate.
 *
 * @see [[akka.transactor.Coordinated]]
 * @see [[akka.transactor.typed.Coordination]]
 */
abstract class Atomically(val factory: TransactionFactory) {
  def this() = this(Coordinated.DefaultFactory)
  def atomically: Unit
}
