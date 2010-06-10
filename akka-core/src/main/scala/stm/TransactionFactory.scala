/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.util.Duration

import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.templates.TransactionBoilerplate
import org.multiverse.api.TraceLevel

/**
 * For configuring multiverse transactions.
 */ 
object TransactionConfig {
  val FAMILY_NAME      = "DefaultTransaction"
  val READONLY         = false
  val MAX_RETRIES      = config.getInt("akka.stm.max-retries", 1000)
  val TIMEOUT          = config.getLong("akka.stm.timeout", Long.MaxValue)
  val TIME_UNIT        = config.getString("akka.stm.time-unit", "seconds")
  val TRACK_READS      = config.getBool("akka.stm.track-reads", false)
  val WRITE_SKEW       = config.getBool("akka.stm.write-skew", true)
  val EXPLICIT_RETRIES = config.getBool("akka.stm.explicit-retries", false)
  val INTERRUPTIBLE    = config.getBool("akka.stm.interruptible", false)
  val SPECULATIVE      = config.getBool("akka.stm.speculative", false)
  val QUICK_RELEASE    = config.getBool("akka.stm.quick-release", true)
  val TRACE_LEVEL      = traceLevel(config.getString("akka.stm.trace-level", "none"))
  val HOOKS            = config.getBool("akka.stm.hooks", true)

  val DefaultTimeout = Duration(TIMEOUT, TIME_UNIT)

  def traceLevel(level: String) = level.toLowerCase match {
    case "coarse" | "course" => Transaction.TraceLevel.Coarse
    case "fine" => Transaction.TraceLevel.Fine
    case _ => Transaction.TraceLevel.None
  }

  def apply(familyName: String       = FAMILY_NAME,
            readonly: Boolean        = READONLY,
            maxRetries: Int          = MAX_RETRIES,
            timeout: Duration        = DefaultTimeout,
            trackReads: Boolean      = TRACK_READS,
            writeSkew: Boolean       = WRITE_SKEW,
            explicitRetries: Boolean = EXPLICIT_RETRIES,
            interruptible: Boolean   = INTERRUPTIBLE,
            speculative: Boolean     = SPECULATIVE,
            quickRelease: Boolean    = QUICK_RELEASE,
            traceLevel: TraceLevel   = TRACE_LEVEL,
            hooks: Boolean           = HOOKS) = {
    new TransactionConfig(familyName, readonly, maxRetries, timeout, trackReads, writeSkew,
                          explicitRetries, interruptible, speculative, quickRelease, traceLevel, hooks)
  }
}

/**
 * For configuring multiverse transactions.
 */
class TransactionConfig(val familyName: String       = TransactionConfig.FAMILY_NAME,
                        val readonly: Boolean        = TransactionConfig.READONLY,
                        val maxRetries: Int          = TransactionConfig.MAX_RETRIES,
                        val timeout: Duration        = TransactionConfig.DefaultTimeout,
                        val trackReads: Boolean      = TransactionConfig.TRACK_READS,
                        val writeSkew: Boolean       = TransactionConfig.WRITE_SKEW,
                        val explicitRetries: Boolean = TransactionConfig.EXPLICIT_RETRIES,
                        val interruptible: Boolean   = TransactionConfig.INTERRUPTIBLE,
                        val speculative: Boolean     = TransactionConfig.SPECULATIVE,
                        val quickRelease: Boolean    = TransactionConfig.QUICK_RELEASE,
                        val traceLevel: TraceLevel   = TransactionConfig.TRACE_LEVEL,
                        val hooks: Boolean           = TransactionConfig.HOOKS) 

object DefaultTransactionConfig extends TransactionConfig

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 */ 
object TransactionFactory {
  def apply(config: TransactionConfig) = new TransactionFactory(config)

  def apply(config: TransactionConfig, defaultName: String) = new TransactionFactory(config, defaultName)

  def apply(familyName: String       = TransactionConfig.FAMILY_NAME,
            readonly: Boolean        = TransactionConfig.READONLY,
            maxRetries: Int          = TransactionConfig.MAX_RETRIES,
            timeout: Duration        = TransactionConfig.DefaultTimeout,
            trackReads: Boolean      = TransactionConfig.TRACK_READS,
            writeSkew: Boolean       = TransactionConfig.WRITE_SKEW,
            explicitRetries: Boolean = TransactionConfig.EXPLICIT_RETRIES,
            interruptible: Boolean   = TransactionConfig.INTERRUPTIBLE,
            speculative: Boolean     = TransactionConfig.SPECULATIVE,
            quickRelease: Boolean    = TransactionConfig.QUICK_RELEASE,
            traceLevel: TraceLevel   = TransactionConfig.TRACE_LEVEL,
            hooks: Boolean           = TransactionConfig.HOOKS) = {
    val config = new TransactionConfig(familyName, readonly, maxRetries, timeout, trackReads, writeSkew,
                                        explicitRetries, interruptible, speculative, quickRelease, traceLevel, hooks)
    new TransactionFactory(config)
  }
}

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 */ 
class TransactionFactory(val config: TransactionConfig = DefaultTransactionConfig, defaultName: String = TransactionConfig.FAMILY_NAME) {
  self =>

  // use the config family name if it's been set, otherwise defaultName - used by actors to set class name as default
  val familyName = if (config.familyName != TransactionConfig.FAMILY_NAME) config.familyName else defaultName

  val factory = (getGlobalStmInstance().asInstanceOf[AlphaStm].getTransactionFactoryBuilder()
                 .setFamilyName(familyName)
                 .setReadonly(config.readonly)
                 .setMaxRetries(config.maxRetries)
                 .setTimeoutNs(config.timeout.toNanos)
                 .setReadTrackingEnabled(config.trackReads)
                 .setWriteSkewAllowed(config.writeSkew)
                 .setExplicitRetryAllowed(config.explicitRetries)
                 .setInterruptible(config.interruptible)
                 .setSpeculativeConfigurationEnabled(config.speculative)
                 .setQuickReleaseEnabled(config.quickRelease)
                 .setTraceLevel(config.traceLevel)
                 .build())

  val boilerplate = new TransactionBoilerplate(factory)

  def addHooks = if (config.hooks) Transaction.attach
}
