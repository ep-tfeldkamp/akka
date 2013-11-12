/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.util._

import akka.actor.Actor
import akka.pattern.{ pipe, PromiseActorRef }
import akka.persistence._

/**
 * Abstract journal, optimized for synchronous writes.
 */
trait SyncWriteJournal extends Actor with AsyncReplay {
  import JournalProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)

  final def receive = {
    case Write(persistent, processor) ⇒ {
      val sdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      Try(write(persistent.prepareWrite(sdr))) match {
        case Success(_) ⇒ processor forward WriteSuccess(persistent)
        case Failure(e) ⇒ processor forward WriteFailure(persistent, e); throw e
      }
    }
    case WriteBatch(persistentBatch, processor) ⇒ {
      val sdr = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      Try(writeBatch(persistentBatch.map(_.prepareWrite(sdr)))) match {
        case Success(_) ⇒ persistentBatch.foreach(processor forward WriteSuccess(_))
        case Failure(e) ⇒ persistentBatch.foreach(processor forward WriteFailure(_, e)); throw e
      }
    }
    case Replay(fromSequenceNr, toSequenceNr, processorId, processor) ⇒ {
      replayAsync(processorId, fromSequenceNr, toSequenceNr) { p ⇒
        if (!p.deleted) processor.tell(Replayed(p), p.sender)
      } map {
        maxSnr ⇒ ReplaySuccess(maxSnr)
      } recover {
        case e ⇒ ReplayFailure(e)
      } pipeTo (processor)
    }
    case c @ Confirm(processorId, sequenceNr, channelId) ⇒ {
      confirm(processorId, sequenceNr, channelId)
      if (extension.publishPluginCommands) context.system.eventStream.publish(c)
    }
    case d @ Delete(processorId, fromSequenceNr, toSequenceNr, permanent) ⇒ {
      delete(processorId, fromSequenceNr, toSequenceNr, permanent)
      if (extension.publishPluginCommands) context.system.eventStream.publish(d)
    }
    case Loop(message, processor) ⇒ {
      processor forward LoopSuccess(message)
    }
  }

  //#journal-plugin-api
  /**
   * Plugin API: synchronously writes a `persistent` message to the journal.
   */
  def write(persistent: PersistentRepr): Unit

  /**
   * Plugin API: synchronously writes a batch of persistent messages to the journal.
   * The batch write must be atomic i.e. either all persistent messages in the batch
   * are written or none.
   */
  def writeBatch(persistentBatch: immutable.Seq[PersistentRepr]): Unit

  /**
   * Plugin API: synchronously deletes all persistent messages within the range from
   * `fromSequenceNr` to `toSequenceNr` (both inclusive). If `permanent` is set to
   * `false`, the persistent messages are marked as deleted, otherwise they are
   * permanently deleted.
   *
   * @see [[AsyncReplay]]
   */
  def delete(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean): Unit

  /**
   * Plugin API: synchronously writes a delivery confirmation to the journal.
   */
  def confirm(processorId: String, sequenceNr: Long, channelId: String): Unit
  //#journal-plugin-api
}
