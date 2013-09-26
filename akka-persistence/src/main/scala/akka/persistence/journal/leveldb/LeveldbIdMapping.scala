/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.leveldb

import akka.actor.Actor

import org.iq80.leveldb.DBIterator

/**
 * Persistent mapping of `String`-based processor and channel ids to numeric ids.
 */
private[persistence] trait LeveldbIdMapping extends Actor { this: LeveldbJournal ⇒
  import Key._

  private val idOffset = 10
  private var idMap: Map[String, Int] = Map.empty

  /**
   * Get the mapped numeric id for the specified processor or channel `id`. Creates and
   * stores a new mapping if necessary.
   */
  def numericId(id: String): Int = idMap.get(id) match {
    case None    ⇒ writeIdMapping(id, idMap.size + idOffset)
    case Some(v) ⇒ v
  }

  private def readIdMap(): Map[String, Int] = {
    val iter = leveldbIterator
    try {
      iter.seek(keyToBytes(idKey(idOffset)))
      readIdMap(Map.empty, iter)
    } finally {
      iter.close()
    }
  }

  private def readIdMap(pathMap: Map[String, Int], iter: DBIterator): Map[String, Int] = {
    if (!iter.hasNext) pathMap else {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (!isIdKey(nextKey)) pathMap else {
        val nextVal = new String(nextEntry.getValue, "UTF-8")
        readIdMap(pathMap + (nextVal -> id(nextKey)), iter)
      }
    }
  }

  private def writeIdMapping(id: String, numericId: Int): Int = {
    idMap = idMap + (id -> numericId)
    leveldb.put(keyToBytes(idKey(numericId)), id.getBytes("UTF-8"))
    numericId
  }

  override def preStart() {
    idMap = readIdMap()
    super.preStart()
  }
}
