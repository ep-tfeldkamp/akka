/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

import akka.AkkaException
import akka.actor.EventHandler

import java.net.InetSocketAddress
import java.lang.reflect.Method

class ConfigurationException(message: String) extends AkkaException(message)
class ModuleNotAvailableException(message: String) extends AkkaException(message)

/**
 * Loads up the configuration (from the akka.conf file).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Config {
  val VERSION = "1.1-SNAPSHOT"

  val HOME = {
    val envHome = System.getenv("AKKA_HOME") match {
      case null | "" | "." => None
      case value           => Some(value)
    }

    val systemHome = System.getProperty("akka.home") match {
      case null | "" => None
      case value     => Some(value)
    }

    envHome orElse systemHome
  }

  val config = {

    val confName = {

      val envConf = System.getenv("AKKA_MODE") match {
        case null | "" => None
        case value     => Some(value)
      }

      val systemConf = System.getProperty("akka.mode") match {
        case null | "" => None
        case value     => Some(value)
      }

      (envConf orElse systemConf).map("akka." + _ + ".conf").getOrElse("akka.conf")
    }

    try {
      if (System.getProperty("akka.config", "") != "") {
        val configFile = System.getProperty("akka.config", "")
        println("Loading config from -Dakka.config=" + configFile)
        Configuration.fromFile(configFile)
      } else if (getClass.getClassLoader.getResource(confName) ne null) {
        println("Loading config [" + confName + "] from the application classpath.")
        Configuration.fromResource(confName, getClass.getClassLoader)
      } else if (HOME.isDefined) {
        val configFile = HOME.get + "/config/" + confName
        println("AKKA_HOME is defined as [" + HOME.get + "], loading config from [" + configFile + "].")
        Configuration.fromFile(configFile)
      } else {
        println(
          "\nCan't load '" + confName + "'." +
          "\nOne of the three ways of locating the '" + confName + "' file needs to be defined:" +
          "\n\t1. Define the '-Dakka.config=...' system property option." +
          "\n\t2. Put the '" + confName + "' file on the classpath." +
          "\n\t3. Define 'AKKA_HOME' environment variable pointing to the root of the Akka distribution." +
          "\nI have no way of finding the '" + confName + "' configuration file." +
          "\nUsing default values everywhere.")
        Configuration.fromString("akka {}") // default empty config
      }
    } catch {
      case e =>
        EventHandler.error(e, this, e.getMessage)
        throw e
    }
  }

  val CONFIG_VERSION = config.getString("akka.version", VERSION)

  if (VERSION != CONFIG_VERSION) throw new ConfigurationException(
    "Akka JAR version [" + VERSION + "] is different than the provided config version [" + CONFIG_VERSION + "]")

  val TIME_UNIT = config.getString("akka.time-unit", "seconds")

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000
}
