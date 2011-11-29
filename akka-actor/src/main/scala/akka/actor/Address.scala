/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import java.net.URI
import java.net.URISyntaxException

/**
 * The address specifies the physical location under which an Actor can be
 * reached. Examples are local addresses, identified by the ActorSystem’s
 * name, and remote addresses, identified by protocol, host and port.
 */
abstract class Address {
  def protocol: String
  def hostPort: String
  @transient
  override lazy val toString = protocol + "://" + hostPort
}

case class LocalAddress(systemName: String) extends Address {
  def protocol = "akka"
  def hostPort = systemName
}

object LocalActorPath {
  def unapply(addr: String): Option[(LocalAddress, Iterable[String])] = {
    try {
      val uri = new URI(addr)
      if (uri.getScheme != "akka") return None
      if (uri.getUserInfo != null) return None
      if (uri.getHost == null) return None
      if (uri.getPath == null) return None
      Some(LocalAddress(uri.getHost), uri.getPath.split("/").drop(1))
    } catch {
      case _: URISyntaxException ⇒ None
    }
  }
}