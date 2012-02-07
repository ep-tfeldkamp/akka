/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.AkkaException
import akka.util.ReflectiveAccess
import scala.util.DynamicVariable
import com.typesafe.config.Config
import akka.actor.{ Extension, ActorSystem, ExtendedActorSystem, Address }
import java.util.concurrent.ConcurrentHashMap
import akka.event.Logging
import scala.collection.mutable.ArrayBuffer
import java.io.NotSerializableException

case class NoSerializerFoundException(m: String) extends AkkaException(m)

object Serialization {

  /**
   * Tuple that represents mapping from Class to Serializer
   */
  type ClassSerializer = (Class[_], Serializer)

  /**
   * This holds a reference to the current ActorSystem (the surrounding context)
   * during serialization and deserialization.
   *
   * If you are using Serializers yourself, outside of SerializationExtension,
   * you'll need to surround the serialization/deserialization with:
   *
   * currentSystem.withValue(system) {
   *   ...code...
   * }
   */
  val currentSystem = new DynamicVariable[ActorSystem](null)

  /**
   * This holds a reference to the current transport address to be inserted
   * into local actor refs during serialization.
   */
  val currentTransportAddress = new DynamicVariable[Address](null)

  class Settings(val config: Config) {

    import scala.collection.JavaConverters._
    import config._

    val Serializers: Map[String, String] = configToMap(getConfig("akka.actor.serializers"))

    val SerializationBindings: Map[String, String] = configToMap(getConfig("akka.actor.serialization-bindings"))

    private def configToMap(cfg: Config): Map[String, String] =
      cfg.root.unwrapped.asScala.toMap.map { case (k, v) ⇒ (k, v.toString) }

  }
}

/**
 * Serialization module. Contains methods for serialization and deserialization as well as
 * locating a Serializer for a particular class as defined in the mapping in the configuration.
 */
class Serialization(val system: ExtendedActorSystem) extends Extension {
  import Serialization._

  val settings = new Settings(system.settings.config)
  val log = Logging(system, getClass.getName)

  /**
   * Serializes the given AnyRef/java.lang.Object according to the Serialization configuration
   * to either an Array of Bytes or an Exception if one was thrown.
   */
  def serialize(o: AnyRef): Either[Exception, Array[Byte]] =
    try { Right(findSerializerFor(o).toBinary(o)) } catch { case e: Exception ⇒ Left(e) }

  /**
   * Deserializes the given array of bytes using the specified serializer id,
   * using the optional type hint to the Serializer and the optional ClassLoader ot load it into.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(bytes: Array[Byte],
                  serializerId: Int,
                  clazz: Option[Class[_]],
                  classLoader: ClassLoader): Either[Exception, AnyRef] =
    try {
      currentSystem.withValue(system) {
        Right(serializerByIdentity(serializerId).fromBinary(bytes, clazz, Some(classLoader)))
      }
    } catch { case e: Exception ⇒ Left(e) }

  /**
   * Deserializes the given array of bytes using the specified type to look up what Serializer should be used.
   * You can specify an optional ClassLoader to load the object into.
   * Returns either the resulting object or an Exception if one was thrown.
   */
  def deserialize(
    bytes: Array[Byte],
    clazz: Class[_],
    classLoader: Option[ClassLoader]): Either[Exception, AnyRef] =
    try {
      currentSystem.withValue(system) { Right(serializerFor(clazz).fromBinary(bytes, Some(clazz), classLoader)) }
    } catch { case e: Exception ⇒ Left(e) }

  /**
   * Returns the Serializer configured for the given object, returns the NullSerializer if it's null.
   *
   * @throws akka.config.ConfigurationException if no `serialization-bindings` is configured for the
   *   class of the object
   */
  def findSerializerFor(o: AnyRef): Serializer = o match {
    case null  ⇒ NullSerializer
    case other ⇒ serializerFor(other.getClass)
  }

  /**
   * Returns the configured Serializer for the given Class. The configured Serializer
   * is used if the configured class `isAssignableFrom` from the `clazz`, i.e.
   * the configured class is a super class or implemented interface. In case of
   * ambiguity it is primarily using the most specific configured class,
   * and secondly the entry configured first.
   *
   * @throws java.io.NotSerializableException if no `serialization-bindings` is configured for the class
   */
  def serializerFor(clazz: Class[_]): Serializer =
    serializerMap.get(clazz) match {
      case null ⇒
        // bindings are ordered from most specific to least specific
        def unique(cs: Seq[Class[_]], ser: Set[Serializer]): Boolean = (cs forall (_ isAssignableFrom cs(0))) || ser.size == 1

        val possible = bindings filter { _._1 isAssignableFrom clazz }
        possible.size match {
          case 0 ⇒
            throw new NotSerializableException("No configured serialization-bindings for class [%s]" format clazz.getName)
          case x if x == 1 || unique(possible map (_._1), possible.map(_._2)(scala.collection.breakOut)) ⇒
            val ser = possible(0)._2
            serializerMap.putIfAbsent(clazz, ser) match {
              case null ⇒
                log.debug("Using serializer[{}] for message [{}]", ser.getClass.getName, clazz.getName)
                ser
              case some ⇒ some
            }
          case _ ⇒
            throw new NotSerializableException("Multiple serializers found for " + clazz + ": " + possible)
        }
      case ser ⇒ ser
    }

  /**
   * Tries to instantiate the specified Serializer by the FQN
   */
  def serializerOf(serializerFQN: String): Either[Exception, Serializer] =
    ReflectiveAccess.createInstance(serializerFQN, ReflectiveAccess.noParams, ReflectiveAccess.noArgs, system.internalClassLoader)

  /**
   * A Map of serializer from alias to implementation (class implementing akka.serialization.Serializer)
   * By default always contains the following mapping: "java" -> akka.serialization.JavaSerializer
   */
  private val serializers: Map[String, Serializer] = {
    for ((k: String, v: String) ← settings.Serializers)
      yield k -> serializerOf(v).fold(throw _, identity)
  }

  /**
   *  bindings is a Seq of tuple representing the mapping from Class to Serializer.
   *  It is primarily ordered by the most specific classes first, and secondly in the configured order.
   */
  private[akka] val bindings: Seq[ClassSerializer] = {
    val configuredBindings = for ((k: String, v: String) ← settings.SerializationBindings if v != "none") yield {
      val c = ReflectiveAccess.getClassFor(k, system.internalClassLoader).fold(throw _, identity[Class[_]])
      (c, serializers(v))
    }
    sort(configuredBindings)
  }

  /**
   * Sort so that subtypes always precede their supertypes, but without
   * obeying any order between unrelated subtypes (insert sort).
   */
  private def sort(in: Iterable[ClassSerializer]): Seq[ClassSerializer] =
    (new ArrayBuffer[ClassSerializer](in.size) /: in) { (buf, ca) ⇒
      buf.indexWhere(_._1 isAssignableFrom ca._1) match {
        case -1 ⇒ buf append ca
        case x  ⇒ buf insert (x, ca)
      }
      buf
    }

  /**
   * serializerMap is a Map whose keys is the class that is serializable and values is the serializer
   * to be used for that class.
   */
  private val serializerMap: ConcurrentHashMap[Class[_], Serializer] = {
    val serializerMap = new ConcurrentHashMap[Class[_], Serializer]
    for ((c, s) ← bindings) serializerMap.put(c, s)
    serializerMap
  }

  /**
   * Maps from a Serializer Identity (Int) to a Serializer instance (optimization)
   */
  val serializerByIdentity: Map[Int, Serializer] =
    Map(NullSerializer.identifier -> NullSerializer) ++ serializers map { case (_, v) ⇒ (v.identifier, v) }
}

